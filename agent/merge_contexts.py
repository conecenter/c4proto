import json
import os

import yaml

empty_config = {
    'apiVersion': 'v1', 'clusters': [], 'contexts': [],
    "current-context": "", "kind": "Config", "preferences": {},
    'users': []
}


def merge_contexts(config_file, c4_contexts_file):
    """
    Merges Kubernetes context information from a revised c4contexts.json into an
    existing kubectl config file (in YAML format).  If the config file does not
    exist, it creates one with the merged information.  This version of the
    script assumes that c4_contexts.json has a flat structure where each entry
    contains all the necessary cluster, user, and context information.

    Args:
        config_file (str): Path to the kubectl config file.
        c4_contexts_file (str): Path to the c4contexts.json file.
    """
    # Load c4_contexts data
    with open(c4_contexts_file, 'r') as f:
        c4_data = json.load(f)

    # Load the existing config file or create a new one if it doesn't exist
    if os.path.exists(config_file):
        try:
            with open(config_file, 'r') as f:
                config = yaml.safe_load(f)
                if config is None:
                    config = empty_config
        except yaml.YAMLError as e:
            print(f"Error reading config file: {e}.  Creating a new one.")
            config = empty_config
    else:
        print(f"Config file not found at {config_file}. Creating a new one.")
        config = empty_config

    # Ensure necessary sections exist
    if 'clusters' not in config:
        config['clusters'] = []
    if 'contexts' not in config:
        config['contexts'] = []
    if 'users' not in config:
        config['users'] = []

    existing_clusters = {c['name']: c['cluster'] for c in config.get('clusters', [])}
    existing_contexts = {c['name']: c['context'] for c in config.get('contexts', [])}
    existing_users = {u['name']: u['user'] for u in config.get('users', [])}

    changes = {
        'clusters': {'added': [], 'updated': []},
        'contexts': {'added': [], 'updated': []},
        'users': {'added': [], 'updated': []},
    }

    # Iterate through c4_data and merge/add information
    for item in c4_data:
        cluster_name = item['cluster_name']  # Use context name as cluster name
        user_name = cluster_name
        context_name = item['name']

        # Cluster processing
        if cluster_name not in existing_clusters:
            new_cluster = {
                'cluster': {
                    'server': item['server'],
                    'certificate-authority': item['certificate-authority'],
                },
                'name': cluster_name,
            }
            config['clusters'].append(new_cluster)
            existing_clusters[cluster_name] = new_cluster['cluster']
            changes['clusters']['added'].append(new_cluster)
        else:
            # In this version, clusters are not updated, so nothing to do
            pass

        # User processing
        if user_name not in existing_users:
            new_user = {
                'user': {
                    'auth-provider': {
                        'name': 'oidc',
                        'config': {
                            'client-id': cluster_name,
                            'client-secret': item['secret'],
                            'id-token': '',  # These should be obtained dynamically
                            'idp-issuer-url': item['issuer'],
                            'refresh-token': ''  # These should be obtained dynamically
                        }
                    }
                },
                'name': user_name,
            }
            config['users'].append(new_user)
            existing_users[user_name] = new_user['user']
            changes['users']['added'].append(new_user)
        else:
            # Users are not updated, so nothing to do.
            pass

        # Context processing
        if context_name not in existing_contexts:
            new_context = {
                'context': {
                    'cluster': cluster_name,
                    'user': user_name,
                    'namespace': item['namespace']
                },
                'name': context_name,
            }
            config['contexts'].append(new_context)
            existing_contexts[cluster_name] = new_context['context']
            changes['contexts']['added'].append(new_context)
        else:
            # update namespace
            original_namespace = existing_contexts[context_name]['namespace']
            config['contexts'][-1]['context']['namespace'] = item['namespace']
            if original_namespace != item['namespace']:
                changes['contexts']['updated'].append(
                    {
                        'name': cluster_name,
                        'old_namespace': original_namespace,
                        'new_namespace': item['namespace']
                    }
                )

    # Write the updated configuration to the file
    with open(config_file, 'w') as f:
        yaml.dump(config, f, sort_keys=False)

    print(f"Successfully merged/created config at {config_file}")
    return changes


if __name__ == "__main__":
    config_file = 'kube/config'  # Replace with your desired config file path
    c4_contexts_file = 'c4contexts.json'  # Replace with your c4_contexts.json file
    changes = merge_contexts(config_file, c4_contexts_file)

    print("\nChanges Applied:")
    # Filter out empty lists before printing
    filtered_changes = {k: v for k, v in changes.items() if v['added'] or v['updated']}
    if filtered_changes:
        print(json.dumps(filtered_changes, indent=2))
    else:
        print("No changes applied.")

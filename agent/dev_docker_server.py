import json
import os
import signal
import subprocess
import threading
import time
from dataclasses import dataclass

import jwt
import kubernetes
import yaml
from authlib.integrations.flask_client import OAuth
from dataclasses_json import dataclass_json, DataClassJsonMixin
from flask import Flask, url_for, redirect, render_template, session, request, jsonify

from merge_contexts import merge_contexts

app = Flask(__name__)
app.secret_key = os.getenv('CLIENT_SECRET', 'very-secret-key')
SERVE_ON = os.getenv('C4AGENT_IP', '0.0.0.0')
CONFIG_LOCATION = os.getenv('KUBECONFIG', '/c4repo/kube/config')

@dataclass_json
@dataclass
class KubernetesContext(DataClassJsonMixin):
    cluster_name: str
    name: str
    namespace: str
    secret: str
    issuer: str
    authenticated: bool = False
    active: bool = False

    @property
    def user(self):
        return self.cluster_name


def load_contexts():
    contexts = []
    for contexts_path in ["/c4repo/dev-docker/c4contexts.json", "./c4contexts.json"]:
        if os.path.exists(contexts_path):
            merge_contexts(CONFIG_LOCATION, contexts_path)
            with open(contexts_path, "r") as f:
                context_list = json.load(f)
                contexts.extend([KubernetesContext.from_dict(context) for context in context_list])
        else:
            print(f"File {contexts_path} not found")
    return {context.name: context for context in contexts}


def guess_user(kubeconfig_path):
    with open(kubeconfig_path, 'r') as f:
        config = yaml.safe_load(f)

    user_entries = [u for u in config['users']]
    for user in user_entries:
        try:
            id_token = user.get("user", {}).get("auth-provider", {}).get("config", {}).get("id-token", "")
            claims = jwt.decode(id_token, options={"verify_signature": False})
            username = claims.get("email", "").split("@")[0]
            return username
        except Exception as e:
            print(e)
            pass
    return None


def init_oauth(app):
    oauth = OAuth(app)
    for name, context in app.contexts.items():
        print(f"Initializing OAuth for context {name}")
        oauth.register(
            name,
            server_metadata_url=f"{context.issuer}/.well-known/openid-configuration",
            client_id=context.cluster_name,
            client_secret=context.secret,
            client_kwargs={
                'scope': 'openid profile email offline_access groups'
            }
        )
    return oauth


def ensure_state_dir():
    """Ensure the state directory exists."""
    state_dir = "/c4repo/dev-docker"
    if not os.path.exists(state_dir):
        os.makedirs(state_dir, exist_ok=True)
    return state_dir


def get_state():
    """Get the current state from the JSON file."""
    state_dir = ensure_state_dir()
    state_file = os.path.join(state_dir, "state.json")
    default_state = {"last_context": None, "last_pod": None, "user": None}

    if os.path.exists(state_file):
        try:
            with open(state_file, "r") as f:
                state = json.load(f)
                # Ensure all expected keys exist in the loaded state
                for key in default_state:
                    if key not in state:
                        state[key] = default_state[key]
                return state
        except json.JSONDecodeError:
            # Return default state if file is corrupted
            return default_state
    else:
        return default_state


def save_state(state):
    """Save the state to the JSON file."""
    state_dir = ensure_state_dir()
    state_file = os.path.join(state_dir, "state.json")
    with open(state_file, "w") as f:
        json.dump(state, f)


def last_context():
    state = get_state()
    name = state.get("last_context")
    if name:
        return app.contexts.get(name)
    else:
        return None


def set_last_context(context):
    state = get_state()
    state["last_context"] = context
    save_state(state)


def last_pod():
    state = get_state()
    return state.get("last_pod")


def set_last_pod(pod):
    state = get_state()
    state["last_pod"] = pod
    save_state(state)


def last_user():
    state = get_state()
    return state.get("user")


def set_last_user(user):
    state = get_state()
    state["user"] = user
    save_state(state)


app.contexts = load_contexts()
app.user = last_user() or guess_user(CONFIG_LOCATION)
app.current_context = last_context()
app.pods = []
app.active_pod = last_pod()
if app.contexts and not app.current_context:
    app.current_context = next(iter(app.contexts.values()))
oauth = init_oauth(app)


@app.route("/login/<string:context>")
def login(context):
    context_auth = oauth.create_client(context)
    redirect_uri = url_for("auth", _external=True)
    session['last_context'] = context
    return context_auth.authorize_redirect(redirect_uri, prompt="consent", nonce=os.urandom(16).hex())


def set_oidc_credentials(
    kubeconfig_path,
    context_name,
    idp_issuer_url,
    client_id,
    client_secret,
    refresh_token,
    id_token
):
    user = app.contexts[context_name].user
    with open(kubeconfig_path, 'r') as f:
        config = yaml.safe_load(f)

    users_entry = config.get('users', None)
    user_entry = next((u for u in users_entry if u['name'] == user), None) if users_entry else None
    if not users_entry:
        config['users'] = []
    if not user_entry:
        user_entry = {'name': user, 'user': {}}
        config['users'].append(user_entry)

    user_entry['user'] = {
        'auth-provider': {
            'name': 'oidc',
            'config': {
                'idp-issuer-url': idp_issuer_url,
                'client-id': client_id,
                'client-secret': client_secret,
                'refresh-token': refresh_token,
                'id-token': id_token
            }
        }
    }

    with open(kubeconfig_path, 'w') as f:
        yaml.safe_dump(config, f)


@app.route("/auth")
def auth():
    context_name = session.pop('last_context', None)
    context = app.contexts[context_name]
    context_auth = oauth.create_client(context_name)
    token = context_auth.authorize_access_token()
    user_info = token.get("userinfo")
    idp_issuer = user_info["iss"]
    client_id = user_info["aud"]
    app.user = user_info["email"].split("@")[0]
    set_last_user(app.user)
    client_secret = app.secret_key
    refresh_token = token.get("refresh_token")
    id_token = token.get("id_token")
    set_oidc_credentials(
        CONFIG_LOCATION, context_name, idp_issuer, client_id, client_secret, refresh_token, id_token
    )
    context.authenticated = True
    check_auth_status()
    return redirect(url_for("index"))


@app.route("/set_context", methods=["POST"])
def set_context():
    check_auth_status()
    context_name = request.json.get("context")
    context = app.contexts.get(context_name)
    if context:
        app.current_context = context
        set_last_context(context_name)
        restart_check_pods()
        return jsonify({"success": True, "current_context": context.cluster_name})
    return jsonify({"success": False, "error": "Context not found"}), 400


@app.route("/get_current_context")
def get_current_context():
    return jsonify({"current_context": app.current_context})


@app.route("/get_pods")
def get_pods():
    return jsonify({"pods": app.pods, "active_pod": app.active_pod})


@app.route("/pod/restart/<pod_name>")
def restart_pod(pod_name):
    # Stub implementation for pod restart
    print(f"Restarting pod: {pod_name}")
    kubernetes.config.load_kube_config(config_file=CONFIG_LOCATION, context=app.current_context.name)
    client = kubernetes.client.CoreV1Api(
        api_client=kubernetes.config.new_client_from_config(context=app.current_context.name)
    )
    response = client.delete_namespaced_pod(pod_name, app.current_context.namespace)
    print(response)
    # Immediately refresh the pods list
    restart_check_pods()
    return jsonify({"success": True, "message": f"Pod {pod_name} restart requested"})


def forward_pod(pod_name):
    if pod_name is not None:
        pod_full_name = f"{app.current_context.name}~{pod_name}"
        call_result = subprocess.call(f"c4forward ${SERVE_ON} ${pod_full_name}", shell=True)
        with open("/tmp/c4pod", "w") as f:
            f.write(pod_full_name)
        print(f"c4forward call result: {call_result}")


@app.route("/pod/switch/<pod_name>")
def switch_pod(pod_name):
    # Set the active pod
    app.active_pod = pod_name
    set_last_pod(pod_name)
    forward_pod(pod_name)
    print(f"Switched to pod: {pod_name}")
    return jsonify({"success": True, "active_pod": app.active_pod})


@app.route("/")
def index():
    # Convert contexts dictionary to list of context objects
    contexts = list(app.contexts.values())
    return render_template(
        'index.html',
        user=app.user,
        contexts=contexts,
        current_context=app.current_context.cluster_name,
    )


def check_auth_status():
    for context in app.contexts.values():
        try:
            kubernetes.config.load_kube_config(config_file=CONFIG_LOCATION, context=context)
            client = kubernetes.config.new_client_from_config(context=context)
            api_version = kubernetes.client.VersionApi(client).get_code()
            print(api_version)
            context.authenticated = True
            print(f"context {context.name} authenticated")
        except Exception as e:
            print(f"context {context.name} authentication failed")
            pass


app.check_pods_running = True
app.monitor_c4pod_running = True


def monitor_c4pod():
    """
    Monitor the /tmp/c4pod file for changes made by external processes.
    If changes are detected, update last_pod and call forward_pod.
    """
    print("Starting c4pod monitor thread")
    last_content = None

    # Try to read the initial content
    try:
        if os.path.exists("/tmp/c4pod"):
            with open("/tmp/c4pod", "r") as f:
                last_content = f.read().strip()
    except Exception as e:
        print(f"Error reading /tmp/c4pod: {e}")

    while app.monitor_c4pod_running:
        try:
            # Check if the file exists
            if os.path.exists("/tmp/c4pod"):
                # Read the current content
                with open("/tmp/c4pod", "r") as f:
                    current_content = f.read().strip()

                # If the content has changed and it's not empty
                if current_content and current_content != last_content:
                    print(f"Detected change in /tmp/c4pod: {current_content}")
                    last_content = current_content

                    # Extract the context and pod name from the content (format: context~pod_name)
                    if "~" in current_content:
                        parts = current_content.split("~")
                        context_name = parts[0]
                        pod_name = parts[1]

                        # Check if the server state already matches the content
                        server_state_matches = (context_name == app.current_context.name and pod_name == app.active_pod)

                        if not server_state_matches:
                            print(f"Server state doesn't match /tmp/c4pod content. Updating...")

                            # Check if the context needs to be updated
                            if context_name != app.current_context.name:
                                print(f"Switching context from {app.current_context.name} to {context_name}")
                                # Get the context object from app.contexts
                                context = app.contexts.get(context_name)
                                if context:
                                    # Update the current context
                                    app.current_context = context
                                    set_last_context(context_name)
                                    # Restart the pod checking thread with the new context
                                    restart_check_pods()
                                else:
                                    print(f"Context {context_name} not found in app.contexts")

                            # Check if the pod needs to be updated
                            if pod_name != app.active_pod:
                                print(f"Switching pod from {app.active_pod} to {pod_name}")
                                # Update last_pod and app.active_pod
                                app.active_pod = pod_name
                                set_last_pod(pod_name)

                                # Call forward_pod to ensure the forwarding is active
                                forward_pod(pod_name)
                        else:
                            print(f"Server state already matches /tmp/c4pod content. No changes needed.")

            # Sleep for a short time before checking again
            time.sleep(2)
        except Exception as e:
            print(f"Error in monitor_c4pod: {e}")
            time.sleep(5)  # Wait a bit longer if there was an error


def check_pods():
    kubernetes.config.load_kube_config(config_file=CONFIG_LOCATION, context=app.current_context.name)
    print(f"Starting pod check thread with {app.current_context.name}")
    client = kubernetes.client.CoreV1Api(
        api_client=kubernetes.config.new_client_from_config(context=app.current_context.name)
    )
    refresh_count = 0
    user = f"de-{app.user}"
    while app.check_pods_running:
        if refresh_count % 10 == 0 and app.current_context.authenticated:
            print("Refreshing pods")
            # Clear the pods list before refreshing
            app.pods = []
            for pod in client.list_namespaced_pod(namespace=app.current_context.namespace, watch=False).items:
                if user in pod.metadata.name:
                    # Calculate pod age in hours and minutes
                    creation_time = pod.metadata.creation_timestamp
                    # Use UTC timestamps to avoid timezone issues
                    current_time = time.time()
                    # Convert to UTC timestamp without timezone adjustment
                    creation_timestamp = creation_time.timestamp()
                    age_seconds = current_time - creation_timestamp
                    age_hours = int(age_seconds / 3600)
                    age_minutes = int((age_seconds % 3600) / 60)
                    age_str = f"{age_hours}h {age_minutes}m"

                    # Get restart count from the first container
                    restart_count = 0
                    if pod.status.container_statuses:
                        restart_count = pod.status.container_statuses[0].restart_count

                    # Store pod details as a dictionary
                    pod_info = {
                        'name': pod.metadata.name,
                        'status': pod.status.phase,
                        'age': age_str,
                        'restart': restart_count
                    }
                    app.pods.append(pod_info)
            print(app.pods)
        refresh_count += 1
        time.sleep(2)
    print("Pod check thread stopped")


app.check_pods_thread = threading.Thread(target=check_pods)
app.monitor_c4pod_thread = threading.Thread(target=monitor_c4pod)


def restart_check_pods():
    app.check_pods_running = False
    app.check_pods_thread.join()
    app.check_pods_thread = threading.Thread(target=check_pods)
    app.check_pods_running = True
    app.check_pods_thread.start()


def signal_handler(sig, frame):
    app.check_pods_running = False
    app.monitor_c4pod_running = False


if __name__ == '__main__':
    signal.signal(signal.SIGINT, signal_handler)
    signal.signal(signal.SIGTERM, signal_handler)
    check_auth_status()
    app.check_pods_thread.start()
    app.monitor_c4pod_thread.start()
    forward_pod(app.active_pod)
    app.run(debug=False, host=SERVE_ON, port=int(os.getenv('PORT', 1979)))

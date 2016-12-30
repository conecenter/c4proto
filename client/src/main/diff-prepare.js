
export default function DiffPrepare(localState){
    const imm_tree = localState.get()
    function getDeepNode(branch, path){
        for(var pos=0; pos<path.length && branch; pos++) branch = branch[path[pos]]
        return branch;
    }
    var diff, path, current_imm_node
    function jump(arg_path){
        path = arg_path
        current_imm_node = getDeepNode(imm_tree, path) 
    }
    function getOrCreateNext(branch, key){
        if(!branch[key]) branch[key] = {}
        return branch[key]
    }
    function addIfChanged(key, value){
        if(current_imm_node && current_imm_node[key]===value) return;
        if(!diff) diff = {}
        var imm_branch = imm_tree
        var diff_branch = diff
        for(var pos=0; pos<path.length; pos++) {
            diff_branch = getOrCreateNext(diff_branch, path[pos])
            if(imm_branch){
                imm_branch = imm_branch[path[pos]]
                if(!imm_branch) diff_branch = getOrCreateNext(diff_branch, "$set")
            }
        }
        diff_branch[key] = imm_branch ? {"$set":value} : value
    }
    function apply(){ if(diff) localState.update(diff) }
    return ({jump,addIfChanged,apply})
}

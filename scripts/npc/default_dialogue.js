/* Default WZ dialogue for NPCs without a numeric script or seeded shop. */
function start() {
    cm.sendDefault();
    cm.dispose();
}

function action(mode, type, selection) { cm.dispose(); }

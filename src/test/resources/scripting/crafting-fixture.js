var audit = {kind: '', text: '', disposed: false, changes: [], mesos: 100000000, full: false, missing: false, textInput: '1'};
var crafter = {setCS: function() {}, getGender: function() { return 0; }, haveItem: function() { return false; }};
var cm = {
    getPlayer: function() { return crafter; },
    getQuestStatus: function() { return 2; },
    isQuestStarted: function() { return false; },
    isQuestActive: function() { return false; },
    isQuestCompleted: function() { return true; },
    haveItem: function(id, quantity) { return !audit.missing; },
    canHold: function(id, quantity) {
        if (!Number.isInteger(id) || id < 1000000 || (quantity !== undefined && (!Number.isInteger(quantity) || quantity <= 0 || quantity > 32767))) throw Error('Invalid output '+id+' x'+quantity);
        return !audit.full;
    },
    getLevel: function() { return 100; },
    getMeso: function() { return audit.mesos; },
    gainMeso: function(amount) { if (!Number.isInteger(amount)) throw Error('Invalid fee'); audit.changes.push(['meso', amount]); audit.mesos += amount; },
    gainItem: function(id, quantity) { if (!Number.isInteger(id) || id < 1000000 || !Number.isInteger(quantity) || quantity == 0 || Math.abs(quantity) > 32767) throw Error('Invalid transfer '+id+' x'+quantity); audit.changes.push([id, quantity]); },
    dispose: function() { audit.disposed = true; },
    sendSimple: function(s) { audit.kind = 'menu'; audit.text = s; },
    sendYesNo: function(s) { audit.kind = 'yesno'; audit.text = s; },
    sendGetNumber: function(s) { audit.kind = 'number'; audit.text = s; },
    sendGetText: function(s) { audit.kind = 'text'; audit.text = s; },
    sendNext: function(s) { audit.kind = 'next'; audit.text = s; },
    sendOk: function(s) { audit.kind = 'ok'; audit.text = s; },
    getText: function() { return audit.textInput; },
    startQuest: function() {}, removeAll: function() {}
};
// Force the successful branch of legacy stimulator/scroll random rolls.
Math.random = function() { return 0.5; };

function snapshot() {
    var state = {};
    for (var key of Object.getOwnPropertyNames(globalThis)) {
        var value = globalThis[key];
        if (key == 'audit' || value === undefined || value === null || typeof value == 'number'
                || typeof value == 'string' || typeof value == 'boolean' || Array.isArray(value)) {
            if (key != 'NaN' && key != 'Infinity' && key != 'undefined') state[key] = value === undefined ? undefined : JSON.parse(JSON.stringify(value));
        }
    }
    return state;
}

function restore(state) {
    for (var key in state) globalThis[key] = state[key] === undefined ? undefined : JSON.parse(JSON.stringify(state[key]));
}

function walkCrafting(bulkQuantity) {
    if (bulkQuantity === undefined) bulkQuantity = 1;
    var recipes = 0, nodes = 0;
    var transfers = [];
    start();
    function walk(depth) {
        if (++nodes > 3000 || depth > 12) throw Error('Menu loop');
        if (audit.disposed) return;
        if (/undefined|NaN/.test(audit.text)) throw Error('Broken prompt: '+audit.text);
        var saved = snapshot();
        if (audit.kind == 'menu') {
            var choices = Array.from(audit.text.matchAll(/#L(\d+)#/g), function(m) { return Number(m[1]); });
            for (var choice of choices) {
                restore(saved); action(1, 5, choice); walk(depth + 1);
            }
        } else if (audit.kind == 'yesno') {
            action(1, 1, 0);
            if (audit.changes.length) {
                recipes++;
                transfers.push(audit.changes.slice());
                if (!audit.changes.some(function(c) { return c[0] != 'meso' && c[1] > 0; })) throw Error('Consumed materials without a reward');
                for (var failure of ['full', 'missing', 'cancel', 'close']) {
                    restore(saved);
                    audit.changes = [];
                    if (failure == 'full') audit.full = true;
                    if (failure == 'missing') audit.missing = true;
                    action(failure == 'cancel' ? 0 : failure == 'close' ? -1 : 1, 1, 0);
                    if (audit.changes.length) throw Error('Craft mutated inventory on '+failure);
                }
            } else {
                walk(depth + 1);
            }
        } else if (audit.kind == 'number' || audit.kind == 'text') {
            for (var bad of [-1, 0, 101, 2147483647]) {
                restore(saved);
                audit.textInput = String(bad);
                action(1, audit.kind == 'text' ? 2 : 3, bad);
                if (!audit.disposed || audit.changes.length) throw Error('Invalid craft quantity accepted: '+bad);
            }
            restore(saved);
            audit.textInput = String(bulkQuantity);
            action(1, audit.kind == 'text' ? 2 : 3, bulkQuantity); walk(depth + 1);
        } else if (audit.kind == 'next') {
            action(1, 0, 0); walk(depth + 1);
        }
        restore(saved);
    }
    walk(0);
    globalThis.craftingTransfers = transfers;
    return recipes;
}

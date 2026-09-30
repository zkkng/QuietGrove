// In-memory NPC boundary: failed inventory additions behave like a full inventory.
// Quest scripts themselves are loaded unchanged by QuestDialogRegressionTest.
var questId = 0;
var questStates = {};
var items = {};
var slots = {1: 24, 2: 24, 3: 24, 4: 24, 5: 24};
var exp = 0;
var jobId = 1000;
var jobChanges = 0;
var itemChanges = 0;
var disposed = false;
var eventRequests = 0;
var firstJobAllowed = true;

function stackSize(item) {
    if (Math.floor(item / 1000000) != 2 && Math.floor(item / 1000000) != 4) return 1;
    return item == 2060000 ? 2000 : 100;
}

function canFit(ids, amounts) {
    var next = {};
    var used = {};
    Object.keys(items).forEach(function (id) { next[id] = items[id]; });
    for (var i = 0; i < ids.length; i++) {
        next[ids[i]] = (next[ids[i]] || 0) + (amounts ? amounts[i] : 1);
    }
    Object.keys(next).forEach(function (id) {
        var type = Math.floor(Number(id) / 1000000);
        used[type] = (used[type] || 0) + Math.ceil(next[id] / stackSize(Number(id)));
    });
    return Object.keys(used).every(function (type) { return used[type] <= slots[type]; });
}

var player = {
    getHp: function () { return 100; },
    getJob: function () { return {getId: function () { return jobId; }}; },
    changeJob: function (job) { jobId = job.getId(); jobChanges++; },
    resetStats: function () {},
    isRecvPartySearchInviteEnabled: function () { return false; }
};
var qm = {
    c: {getPlayer: function () { return player; }},
    getPlayer: function () { return player; },
    isQuestStarted: function (id) { return questStates[id] == 1; },
    isQuestCompleted: function (id) { return questStates[id] == 2; },
    haveItem: function (id, amount) { return (items[id] || 0) >= (amount || 1); },
    canHold: function (id, amount) { return canFit([id], [amount || 1]); },
    canHoldAll: function (ids, amounts) { return canFit(ids, amounts); },
    gainItem: function (id, amount) {
        if (amount > 0 && !canFit([id], [amount])) return;
        items[id] = Math.max(0, (items[id] || 0) + amount);
        itemChanges++;
    },
    gainExp: function (amount) { exp += amount; },
    forceCompleteQuest: function (id) { questStates[id === undefined ? questId : id] = 2; return true; },
    canGetFirstJob: function () { return firstJobAllowed; },
    getFirstJobStatRequirement: function () { return ''; },
    changeJob: function (job) { jobId = job.getId(); jobChanges++; },
    dispose: function () { disposed = true; },
    sendNext: function () {},
    sendNextPrev: function () {},
    sendPrev: function () {},
    sendOk: function () {},
    sendYesNo: function () {},
    sendSimple: function () {},
    dropMessage: function () {},
    guideHint: function () {},
    isUsingOldPqNpcStyle: function () { return false; },
    getEventManager: function () {
        eventRequests++;
        return {getProperty: function () { return ''; }};
    }
};
var cm = qm;

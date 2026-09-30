/* Traveling Around Maple restoration. Original dialogue/rewards from Quest.wz. */
var talk = qm;
var tour = [{"id": 8053, "npc": 1002101, "map": 104000000, "endNpc": 1081100, "endMap": 110000000, "ask": "Hey, you! Care to experience something totally new?", "yes": ["That's right! I like that attitude. I'll send you directly to Florina Beach. Once you get there, talk to #eRiel#k and she'll take you to another awesome spot.", "Yes, you'll encounter powerful monsters outside town, so if you aren't ready for them, I advise you to stay within town."], "no": ["Oh ho, a young fella who has no wild side! You'd make a great accountant!"], "end": ["Oh, Olaf sent you here? Alright! Have yourself a watermelon here and chill. There's nothing quite like a cold watermelon under the glaring sun."]}, {"id": 8054, "npc": 1081100, "map": 110000000, "endNpc": 9120006, "endMap": 801000300, "ask": "Alright, how was Florina Beach? Did you like it? Ready to go to the next spot?", "yes": ["Alright, I'll send you to Showa Town. It's a very interesting place with a nice view to boot.", "Once you arrive there, talk to #eSkye#n, and she'll take you to another spot.", "Oh yeah, if you step outside, you'll encounter very scary individuals, so if you aren't ready for them, I suggest you to stay inside."], "no": ["Hey, you like it here, huh? Make yourself comfortable here~"], "end": ["Oh, you're here through Riel? Go have yourself a bath at the bathhouse, and you'll find yourself relieving stress.", "In any case, have yourself a skewer and check out the area!"]}, {"id": 8055, "npc": 9120006, "map": 801000300, "endNpc": 2012010, "endMap": 200000200, "ask": "Alright, isn't this place fun? Obviously there are some interesting people in this town. Ready to go to the next spot?", "yes": ["Alright, I'll send you to Orbis the land of the elevated.", "Once you arrive there, talk to #eElma#n, and she'll take you to another spot.", "Oh yeah, if you step outside, you'll encounter cute yet very violent monsters, so be careful of them."], "no": ["Hey,do you really like it here? There's plenty of time, so have yourself a ball here before leaving this place!"], "end": ["Welcome! What else did Skye tell you about this place? Orbis is a place where the air's clean, and everything seems elevated."]}, {"id": 8056, "npc": 2012010, "map": 200000200, "endNpc": 2041004, "endMap": 220000000, "ask": "Alright, how was Orbis? It's hard to find a more beautiful sky than the ones here. Ready to go to the next spot?", "yes": ["Alright, I'll send you to Ludibrium, a toy land to say the least.", "Once you arrive there, talk to #eMarcel#n, and he'll take you to another spot.", "Oh, and, if you step outside, you'll encounter some very adorable monsters, and it won't be such a rosy experience for you. Be careful."], "no": ["Hey,do you really like it here? There's plenty of time, so just relax and enjoy it here!"], "end": ["Welcome! Elma told me of your arrival. Have fun at Ludibrium!"]}, {"id": 8057, "npc": 2041004, "map": 220000000, "endNpc": 2020007, "endMap": 211000000, "ask": "Was Ludibrium a blast? Isn't it just a fun place to be around? Now, ready to go to the next spot?", "yes": ["Alright, I'll send you to El Nath, a land covered in snow.", "Once you arrive there, talk to #eScadur#n, and he'll take you to another spot.", "Oh yes, if you step outside, you'll be encountering massive, violent wolves wandering around town, so unless you're really powerful, you won't even be able to hit them once!"], "no": ["Oh, you like it here, huh? Make yourself comfortable then~", "After taking care of your business here, just talk to me, and I'll take you to another spot."], "end": ["Welcome! Marcel told me of your arrival. Well, there's not much here besides snow, so check it out! Watch your step--it's slippery!"]}, {"id": 8058, "npc": 2020007, "map": 211000000, "endNpc": 2050009, "endMap": 221000000, "ask": "Did you like El Nath? I know it's cold here, but isn't it nice? Ready to go to the next spot?", "yes": ["Alright, I'll send you to the farthest town in the world, Omega Sector.", "Once you arrive there, talk to #eJr. Officer Medin#n, and he'll take you to another spot.", "Oh yes, if you step outside, you'll be encountering strange, out-of-this-world creatures that are creeping up around town, so I advise you not to wander around!"], "no": ["Hmmm... I'm glad to hear that you actually like it here.", "You can always transfer to another town, so have yourself a ball here!"], "end": [" Nice job getting here, a town located Obviously far from anything else. I heard of your arrival through Scadur. There's really nothing much here, so take your time observing the area."]}, {"id": 8059, "npc": 2050009, "map": 221000000, "endNpc": 1002101, "endMap": 104000000, "ask": "Alright, how was Omega Sector? Did you like it there? Well, you seem to have traveled and observed a whole bunch of diverse towns here. Are you ready to go back to Lith Harbor now?", "yes": ["Alright, I'll take you back to Lith Harbor.", "Once you return to Lith Harbor, talk to #eOlaf#n and he may give you something very interesting."], "no": ["You must have some unfinished business to take care of here. Let me know when you're ready to leave this place."], "end": ["Oh, that was fast. How was it? Did you get to realize just how huge this world really is? Alright, here'a small gift for you."]}];
var Quest = Java.type("server.quest.Quest");
var pending = null;
var phase = 0;
var page = 0;
var consumed = false;
function at(npc, map) {
    return talk.getNpc() == npc && talk.getPlayer().getMapId() == map && talk.getPlayer().getMap().containsNPC(npc);
}
function beginStart(row) {
    var q = Quest.getInstance(row.id);
    if (!at(row.npc, row.map) || !q.canStart(talk.getPlayer(), row.npc)) { talk.dispose(); return; }
    pending = row; phase = 1; page = 0;
    talk.sendYesNo(row.ask);
}
function beginEnd(row) {
    var q = Quest.getInstance(row.id);
    if (!at(row.endNpc, row.endMap) || !q.canComplete(talk.getPlayer(), row.endNpc)) { talk.dispose(); return; }
    pending = row; phase = 3; page = 0;
    talk.sendNext(row.end[0]);
}
function respond(mode) {
    if (consumed || pending == null) { talk.dispose(); return; }
    if (mode != 1) {
        if (phase == 1 && mode == 0) talk.sendOk(pending.no.join("\r\n\r\n"));
        talk.dispose(); return;
    }
    if (phase == 1) { phase = 2; page = 0; talk.sendNext(pending.yes[0]); return; }
    var lines = phase == 2 ? pending.yes : pending.end;
    page++;
    if (page < lines.length) { talk.sendNext(lines[page]); return; }
    consumed = true;
    var q = Quest.getInstance(pending.id);
    if (phase == 2) {
        if (!at(pending.npc, pending.map) || !q.canStart(talk.getPlayer(), pending.npc)) { talk.dispose(); return; }
        q.start(talk.getPlayer(), pending.npc);
        if (talk.getQuestStatus(pending.id) == 1) {
            var target = pending.endMap;
            talk.dispose(); talk.warp(target, 0); return;
        }
    } else {
        if (!at(pending.endNpc, pending.endMap) || !q.canComplete(talk.getPlayer(), pending.endNpc)) { talk.dispose(); return; }
        // Native actions preserve the archived reward, inventory checks and nextQuest update.
        q.complete(talk.getPlayer(), pending.endNpc);
        if (talk.getQuestStatus(pending.id) != 2) talk.sendOk("Please make room in your inventory for the tour reward, then talk to me again.");
    }
    talk.dispose();
}
var opened = false;
function start(mode, type, selection) {
    if (!opened) { opened = true; beginStart(tour[6]); }
    else respond(mode);
}
function end(mode, type, selection) {
    if (!opened) { opened = true; beginEnd(tour[6]); }
    else respond(mode);
}

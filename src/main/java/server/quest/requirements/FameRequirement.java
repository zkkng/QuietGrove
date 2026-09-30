package server.quest.requirements;

import client.Character;
import provider.Data;
import provider.DataTool;
import server.quest.Quest;
import server.quest.QuestRequirementType;

/** The WZ pop field is a minimum fame requirement, not a fame reward. */
public final class FameRequirement extends AbstractQuestRequirement {
    private int fame;
    public FameRequirement(Quest quest, Data data) {
        super(QuestRequirementType.FAME);
        processData(data);
    }
    @Override public void processData(Data data) { fame = DataTool.getInt(data); }
    @Override public boolean check(Character chr, Integer npc) { return chr.getFame() >= fame; }
}

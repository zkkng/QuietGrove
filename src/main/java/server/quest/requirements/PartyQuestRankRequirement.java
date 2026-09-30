package server.quest.requirements;
import client.Character;
import provider.Data;
import provider.DataTool;
import server.quest.Quest;
import server.quest.QuestRequirementType;
import server.content.PqRanks;
public final class PartyQuestRankRequirement extends AbstractQuestRequirement {
    private int count;
    public PartyQuestRankRequirement(Quest quest,Data data) { super(QuestRequirementType.PARTY_QUEST_RANK);processData(data); }
    @Override public void processData(Data data) { count=DataTool.getInt(data); }
    @Override public boolean check(Character chr,Integer npc) { return PqRanks.sRanks(chr) >= count; }
}

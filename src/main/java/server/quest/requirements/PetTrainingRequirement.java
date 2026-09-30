package server.quest.requirements;

import client.Character;
import provider.Data;
import server.content.SmartPets;
import server.quest.Quest;
import server.quest.QuestRequirementType;

public final class PetTrainingRequirement extends AbstractQuestRequirement {
    private final int flag;
    public PetTrainingRequirement(Quest quest, Data data, int flag) {
        super(flag == 128 ? QuestRequirementType.PET_RECALL : QuestRequirementType.PET_AUTO_SPEAK);
        this.flag = flag;
    }
    @Override public void processData(Data data) {}
    @Override public boolean check(Character chr, Integer npc) { return SmartPets.untrained(chr, flag) != null; }
}

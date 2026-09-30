package server.quest.requirements;

import client.Character;
import provider.Data;
import provider.DataTool;
import server.quest.Quest;
import server.quest.QuestRequirementType;
import java.util.HashMap;
import java.util.Map;

/** WZ acquire=1 requires an unlocked skill; omitted/zero requires it to be unlearned. */
public final class SkillRequirement extends AbstractQuestRequirement {
    private final Map<Integer, Boolean> skills = new HashMap<>();
    public SkillRequirement(Quest quest, Data data) {
        super(QuestRequirementType.SKILL);
        processData(data);
    }
    @Override public void processData(Data data) {
        skills.clear();
        for (Data entry : data) skills.put(DataTool.getInt(entry.getChildByPath("id")),
                DataTool.getInt(entry.getChildByPath("acquire")) != 0);
    }
    @Override public boolean check(Character chr, Integer npc) {
        for (var entry : skills.entrySet()) {
            // A fourth-job skill unlocked by a book may have zero assigned SP and a positive mastery cap.
            boolean acquired = chr.getSkillLevel(entry.getKey()) > 0 || chr.getMasterLevel(entry.getKey()) > 0;
            if (acquired != entry.getValue()) return false;
        }
        return true;
    }
}

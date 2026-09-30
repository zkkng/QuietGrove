package server.quest.requirements;
import client.Character;
import provider.Data;
import provider.DataTool;
import server.quest.Quest;
import server.quest.QuestRequirementType;
import java.util.HashMap;
import java.util.Map;
public final class MonsterBookCardsRequirement extends AbstractQuestRequirement {
    private final Map<Integer,Integer> cards = new HashMap<>();
    public MonsterBookCardsRequirement(Quest quest, Data data) { super(QuestRequirementType.MONSTER_BOOK_CARDS); processData(data); }
    @Override public void processData(Data data) {
        for (Data row : data) cards.put(DataTool.getInt("id",row),DataTool.getInt("min",row,1));
    }
    @Override public boolean check(Character chr,Integer npc) {
        var owned = chr.getMonsterBook().getCards();
        return cards.entrySet().stream().allMatch(e -> owned.getOrDefault(e.getKey(),0) >= e.getValue());
    }
}

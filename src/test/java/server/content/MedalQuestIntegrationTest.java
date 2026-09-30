package server.content;

import client.Character;
import client.Client;
import client.QuestStatus;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Pet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import server.maps.MapleMap;
import server.quest.Quest;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MedalQuestIntegrationTest {
    @org.junit.jupiter.api.BeforeAll static void itemData() throws Exception { GameplayTestData.initializeItems(); }
    Character chr;Map<Integer,QuestStatus> quests;ContentState state;
    @BeforeEach void player(){
        chr=mock(Character.class,RETURNS_DEEP_STUBS);quests=new HashMap<>();state=new ContentState();
        when(chr.getContentState()).thenReturn(state);when(chr.getLevel()).thenReturn(200);
        when(chr.getQuest(any(Quest.class))).thenAnswer(a->status(((Quest)a.getArgument(0)).getId()));
        when(chr.getQuest(anyInt())).thenAnswer(a->status(a.getArgument(0)));
        doAnswer(a->{QuestStatus q=a.getArgument(0);quests.put((int)q.getQuestID(),q);return null;}).when(chr).updateQuestStatus(any());
        for(var type:InventoryType.values())when(chr.getInventory(type)).thenReturn(mock(Inventory.class));
    }
    QuestStatus status(int id){return quests.computeIfAbsent(id,k->new QuestStatus(Quest.getInstance(k),QuestStatus.Status.NOT_STARTED));}
    @Test void nativeTimedQuestSetsThirtyDayExpirationAndCannotFinishEmpty(){
        Quest q=Quest.getInstance(29400);long before=System.currentTimeMillis();q.start(chr,9000040);
        assertEquals(QuestStatus.Status.STARTED,status(29400).getStatus());
        assertTrue(status(29400).getExpirationTime()>=before+30L*86400000);
        assertFalse(q.canComplete(chr,9000040));state.set("medal.29400.kills",1_000_000);assertTrue(q.canComplete(chr,9000040));
        state.set("medal.29400.start",before-30L*86400000);assertFalse(q.canComplete(chr,9000040));
    }
    @Test void explorationProgressMayExceedThresholdButCannotSkipIt(){
        Quest q=Quest.getInstance(29004);q.start(chr,9000066);
        when(chr.getAbstractPlayerInteraction().getQuestProgress(27018,0)).thenReturn("4");assertFalse(q.canComplete(chr,9000040));
        when(chr.getAbstractPlayerInteraction().getQuestProgress(27018,0)).thenReturn("6");assertTrue(q.canComplete(chr,9000040));
    }
    @Test void autoStartAggregateStillRequiresLevelAndEveryPrerequisite(){
        Quest q=Quest.getInstance(29012);when(chr.getLevel()).thenReturn(59);q.start(chr,9000066);assertEquals(QuestStatus.Status.NOT_STARTED,status(29012).getStatus());
        when(chr.getLevel()).thenReturn(60);q.start(chr,9000066);assertEquals(QuestStatus.Status.STARTED,status(29012).getStatus());
        assertFalse(q.canComplete(chr,9000040));
        for(int id=29006;id<=29011;id++)status(id).setStatus(QuestStatus.Status.COMPLETED);
        assertTrue(q.canComplete(chr,9000040));status(29009).setStatus(QuestStatus.Status.STARTED);assertFalse(q.canComplete(chr,9000040));
    }
    @Test void retryMissingPlaceholderRestartsRequirementsWithoutFreeReward(){
        status(29400).setStatus(QuestStatus.Status.COMPLETED);Medals.retryMissing(chr,29400);
        assertEquals(QuestStatus.Status.STARTED,status(29400).getStatus());assertEquals(0,state.get("medal.29400.kills"));
        status(29400).setStatus(QuestStatus.Status.COMPLETED);when(chr.haveItem(Quest.getInstance(29400).getMedalRequirement())).thenReturn(true);
        Medals.retryMissing(chr,29400);assertEquals(QuestStatus.Status.COMPLETED,status(29400).getStatus());
    }
    @ParameterizedTest @ValueSource(ints={4660,4661})
    void nativePetQuestRequiresSummonedEligiblePetAndJuice(int id){
        Quest q=Quest.getInstance(id);Pet pet=mock(Pet.class);when(chr.getPets()).thenReturn(new Pet[]{pet,null,null});when(pet.getItemId()).thenReturn(5000048);
        q.start(chr,1012006);assertEquals(QuestStatus.Status.NOT_STARTED,status(id).getStatus());
        when(pet.isSummoned()).thenReturn(true);q.start(chr,1012006);assertEquals(QuestStatus.Status.STARTED,status(id).getStatus());
        assertFalse(q.canComplete(chr,1012006));
        when(chr.getInventory(InventoryType.ETC).listById(4031993)).thenReturn(java.util.List.of(new client.inventory.Item(4031993,(short)1,(short)1)));
        assertTrue(q.canComplete(chr,1012006));when(pet.getPetAttribute()).thenReturn(id==4660?128:256);assertFalse(q.canComplete(chr,1012006));
    }
}

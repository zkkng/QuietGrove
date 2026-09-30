package server.events.gm;

import client.Character;
import client.inventory.InventoryType;
import server.ItemInformationProvider;

/** Classic field actions permit basic melee with event damage independent of combat level. */
public final class EventMelee {
    private EventMelee() {}
    public static boolean legal(Character actor) {
        var weapon=actor.getInventory(InventoryType.EQUIPPED).getItem((short)-11);
        if(weapon==null) return false;
        return switch(ItemInformationProvider.getInstance().getWeaponType(weapon.getItemId())) {
            case BOW,CROSSBOW,CLAW,GUN,NOT_A_WEAPON -> false;
            default -> true;
        };
    }
}

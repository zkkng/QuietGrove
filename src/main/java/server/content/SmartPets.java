package server.content;

import client.Character;
import client.inventory.Pet;
import tools.PacketCreator;

public final class SmartPets {
    private SmartPets() {}
    public static boolean isSmart(Pet pet) {
        return pet != null && pet.isSummoned() && pet.getItemId() >= 5000048 && pet.getItemId() <= 5000053;
    }
    public static Pet untrained(Character chr, int skill) {
        for (Pet pet : chr.getPets()) {
            if (isSmart(pet) && (pet.getPetAttribute() & skill) == 0) return pet;
        }
        return null;
    }
    public static boolean recall(Character chr) {
        boolean recalled = false;
        for (Pet pet : chr.getPets()) {
            if (!isSmart(pet) || (pet.getPetAttribute() & Pet.PetAttribute.RECALL.getValue()) == 0) continue;
            pet.setPos(chr.getPosition());
            var foothold = chr.getMap().getFootholds().findBelow(chr.getPosition());
            if (foothold != null) pet.setFh(foothold.getId());
            chr.getMap().broadcastMessage(chr, PacketCreator.showPet(chr, pet, true, false), true);
            chr.getMap().broadcastMessage(chr, PacketCreator.showPet(chr, pet, false, false), true);
            recalled = true;
        }
        return recalled;
    }
}

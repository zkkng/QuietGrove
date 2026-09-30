package server.trainer;

import client.inventory.Equip;
import client.inventory.Item;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/** Exact, bounded item snapshot for a single finite venue asset. */
public final class TrainerVenueItemCodec {
    private static final int MAGIC = 0x54564931; // TVI1
    private static final int MAX_BYTES = 2048;

    private TrainerVenueItemCodec() { }

    public static byte[] encode(Item item) {
        if (item == null || item.getQuantity() != 1 || item.getPetId() >= 0)
            throw new IllegalArgumentException("venue asset must be one non-pet item");
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(MAGIC);
            out.writeBoolean(item instanceof Equip);
            out.writeInt(item.getItemId());
            out.writeShort(item.getFlag());
            out.writeLong(item.getExpiration());
            out.writeInt(item.getSN());
            out.writeUTF(item.getOwner());
            out.writeUTF(item.getGiftFrom());
            if (item instanceof Equip equip) {
                out.writeByte(equip.getUpgradeSlots());
                out.writeByte(equip.getLevel());
                out.writeByte(equip.getItemLevel());
                out.writeInt(equip.getItemExp());
                out.writeInt(equip.getRingId());
                for (short stat : new short[]{equip.getStr(), equip.getDex(), equip.getInt(),
                        equip.getLuk(), equip.getHp(), equip.getMp(), equip.getWatk(),
                        equip.getMatk(), equip.getWdef(), equip.getMdef(), equip.getAcc(),
                        equip.getAvoid(), equip.getHands(), equip.getSpeed(), equip.getJump(),
                        equip.getVicious()}) out.writeShort(stat);
            }
            out.flush();
            byte[] snapshot = bytes.toByteArray();
            if (snapshot.length > MAX_BYTES) throw new IllegalArgumentException("venue asset snapshot too large");
            return snapshot;
        } catch (IOException failure) {
            throw new IllegalStateException("Could not encode venue asset", failure);
        }
    }

    public static Item decode(byte[] snapshot) {
        if (snapshot == null || snapshot.length < 25 || snapshot.length > MAX_BYTES)
            throw new IllegalArgumentException("venue asset snapshot size");
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(snapshot));
            if (in.readInt() != MAGIC) throw new IOException("venue asset format");
            boolean equipment = in.readBoolean();
            int itemId = in.readInt();
            if (itemId <= 0) throw new IOException("venue item id");
            short flag = in.readShort();
            long expiration = in.readLong();
            int sn = in.readInt();
            String owner = in.readUTF();
            String giftFrom = in.readUTF();
            Item item;
            if (equipment) {
                Equip equip = new Equip(itemId, (short) 0, 0);
                equip.setUpgradeSlots(in.readByte());
                equip.setLevel(in.readByte());
                equip.setItemLevel(in.readByte());
                equip.setItemExp(in.readInt());
                equip.setRingId(in.readInt());
                equip.setStr(in.readShort()); equip.setDex(in.readShort());
                equip.setInt(in.readShort()); equip.setLuk(in.readShort());
                equip.setHp(in.readShort()); equip.setMp(in.readShort());
                equip.setWatk(in.readShort()); equip.setMatk(in.readShort());
                equip.setWdef(in.readShort()); equip.setMdef(in.readShort());
                equip.setAcc(in.readShort()); equip.setAvoid(in.readShort());
                equip.setHands(in.readShort()); equip.setSpeed(in.readShort());
                equip.setJump(in.readShort()); equip.setVicious(in.readShort());
                item = equip;
            } else item = new Item(itemId, (short) 0, (short) 1);
            if (in.available() != 0) throw new IOException("trailing venue asset data");
            // Item.setFlag consults WZ/account restriction data. A zero flag is
            // already the constructor default and does not need that lookup.
            if (flag != 0) item.setFlag(flag);
            item.setExpiration(expiration);
            item.setSN(sn);
            item.setOwner(owner);
            item.setGiftFrom(giftFrom);
            return item;
        } catch (IOException failure) {
            throw new IllegalArgumentException("Invalid venue asset snapshot", failure);
        }
    }
}

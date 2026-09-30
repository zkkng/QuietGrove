/*
	This file is part of the OdinMS Maple Story Server
    Copyright (C) 2008 Patrick Huy <patrick.huy@frz.cc>
		       Matthias Butz <matze@odinms.de>
		       Jan Christian Meyer <vimes@odinms.de>

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as
    published by the Free Software Foundation version 3 as published by
    the Free Software Foundation. You may not use, modify or distribute
    this program under any other version of the GNU Affero General Public
    License.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/
package server.life;

/**
 * @author Danny (Leifde)
 */
public class MobAttackInfo {
    private boolean isDeadlyAttack;
    private int mpBurn;
    private int diseaseSkill;
    private int diseaseLevel;
    private int mpCon;
    private java.awt.Point rangeLt, rangeRb;
    private int attackDelay;
    private boolean magic;
    private int radius, bulletSpeed, physicalAttack, magicAttack, targetCount = 1;
    private java.awt.Point projectileOrigin;
    public void setProjectile(int radius, java.awt.Point origin, int speed) {
        this.radius = Math.max(0,radius); this.projectileOrigin = origin; this.bulletSpeed = Math.max(1,speed);
    }
    public boolean isProjectile() { return radius > 0 && projectileOrigin != null; }
    public java.awt.Point projectileStart(java.awt.Point origin, boolean left) {
        return new java.awt.Point(origin.x+(left ? projectileOrigin.x : -projectileOrigin.x),origin.y+projectileOrigin.y);
    }
    public int projectileDelay(java.awt.Point from, java.awt.Point to) {
        return (int)Math.min(10_000,from.distance(to)*1000.0/Math.max(1,bulletSpeed));
    }
    public boolean canTarget(java.awt.Point origin, java.awt.Point target, boolean left) {
        var box = attackBox(origin,left);
        return box != null ? box.contains(target) : isProjectile() && projectileStart(origin,left).distanceSq(target) <= (long)radius*radius;
    }
    public void setAttackPower(int physical, int magic) { physicalAttack = physical; magicAttack = magic; }
    public int getAttackPower() { return magic ? magicAttack : physicalAttack; }
    public void setTargetCount(int count) { targetCount = Math.max(1,Math.min(15,count)); }
    public int getTargetCount() { return targetCount; }
    public void setRange(java.awt.Point lt, java.awt.Point rb) {
        rangeLt = lt == null ? null : new java.awt.Point(lt);
        rangeRb = rb == null ? null : new java.awt.Point(rb);
    }
    public java.awt.Rectangle attackBox(java.awt.Point origin, boolean facingLeft) {
        if (rangeLt == null || rangeRb == null) return null;
        int x1 = facingLeft ? rangeLt.x : -rangeRb.x;
        int x2 = facingLeft ? rangeRb.x : -rangeLt.x;
        return new java.awt.Rectangle(origin.x + Math.min(x1, x2), origin.y + rangeLt.y,
                Math.abs(x2 - x1) + 1, Math.max(1, rangeRb.y - rangeLt.y + 1));
    }
    public int getAttackDelay() { return attackDelay; }
    public void setAttackDelay(int delay) { attackDelay = Math.max(0, delay); }
    public boolean isMagic() { return magic; }
    public void setMagic(boolean magic) { this.magic = magic; }

    public MobAttackInfo(int mobId, int attackId) {
    }

    public void setDeadlyAttack(boolean isDeadlyAttack) {
        this.isDeadlyAttack = isDeadlyAttack;
    }

    public boolean isDeadlyAttack() {
        return isDeadlyAttack;
    }

    public void setMpBurn(int mpBurn) {
        this.mpBurn = mpBurn;
    }

    public int getMpBurn() {
        return mpBurn;
    }

    public void setDiseaseSkill(int diseaseSkill) {
        this.diseaseSkill = diseaseSkill;
    }

    public int getDiseaseSkill() {
        return diseaseSkill;
    }

    public void setDiseaseLevel(int diseaseLevel) {
        this.diseaseLevel = diseaseLevel;
    }

    public int getDiseaseLevel() {
        return diseaseLevel;
    }

    public void setMpCon(int mpCon) {
        this.mpCon = mpCon;
    }

    public int getMpCon() {
        return mpCon;
    }
}

package com.gpl.rpg.AndorsTrail.controller;

import java.util.ArrayList;

import com.gpl.rpg.AndorsTrail.VisualEffectCollection;
import com.gpl.rpg.AndorsTrail.context.ViewContext;
import com.gpl.rpg.AndorsTrail.model.ability.ActorCondition;
import com.gpl.rpg.AndorsTrail.model.ability.ActorConditionEffect;
import com.gpl.rpg.AndorsTrail.model.ability.ActorConditionType;
import com.gpl.rpg.AndorsTrail.model.ability.SkillCollection;
import com.gpl.rpg.AndorsTrail.model.ability.traits.AbilityModifierTraits;
import com.gpl.rpg.AndorsTrail.model.ability.traits.StatsModifierTraits;
import com.gpl.rpg.AndorsTrail.model.actor.Actor;
import com.gpl.rpg.AndorsTrail.model.actor.Monster;
import com.gpl.rpg.AndorsTrail.model.actor.Player;
import com.gpl.rpg.AndorsTrail.model.item.Inventory;
import com.gpl.rpg.AndorsTrail.model.item.ItemTraits_OnEquip;
import com.gpl.rpg.AndorsTrail.model.item.ItemTraits_OnUse;
import com.gpl.rpg.AndorsTrail.model.item.ItemType;
import com.gpl.rpg.AndorsTrail.model.map.PredefinedMap;
import com.gpl.rpg.AndorsTrail.model.map.MonsterSpawnArea;

public class ActorStatsController {
	private final ViewContext view;
    //private final WorldContext world;

	public ActorStatsController(ViewContext context) {
    	this.view = context;
    	//this.world = context;
    }

	public static void addConditionsFromEquippedItem(Player player, ItemType itemType) {
		ItemTraits_OnEquip equipEffects = itemType.effects_equip;
		if (equipEffects == null) return;
		if (equipEffects.addedConditions == null) return;
		for (ActorConditionEffect e : equipEffects.addedConditions) {
			applyActorCondition(player, e, ActorCondition.DURATION_FOREVER);
		}
	}
	public static void removeConditionsFromUnequippedItem(Player player, ItemType itemType) {
		ItemTraits_OnEquip equipEffects = itemType.effects_equip;
		if (equipEffects == null) return;
		if (equipEffects.addedConditions == null) return;
		for (ActorConditionEffect e : equipEffects.addedConditions) {
			if (e.isRemovalEffect()) continue;
			if (e.magnitude <= 0) continue;
			if (e.conditionType.isStacking) {
				removeStackableActorCondition(player, e.conditionType, e.magnitude, ActorCondition.DURATION_FOREVER);
			} else {
				removeNonStackableActorCondition(player, e.conditionType, e.magnitude, ActorCondition.DURATION_FOREVER);
			}
		}
	}

	private static void removeStackableActorCondition(Actor actor, ActorConditionType type, int magnitude, int duration) {
		for(int i = actor.conditions.size() - 1; i >= 0; --i) {
			ActorCondition c = actor.conditions.get(i);
			if (!type.conditionTypeID.equals(c.conditionType.conditionTypeID)) continue;
			if (c.duration != duration) continue;
			
			if (c.magnitude > magnitude) {
				c.magnitude -= magnitude;
				actor.conditionListener.onActorConditionMagnitudeChanged(actor, c);
			} else {
				actor.conditions.remove(i);
				actor.conditionListener.onActorConditionRemoved(actor, c);
			}
			break;
		}
	}

	private static void removeNonStackableActorCondition(Player player, ActorConditionType type, int magnitude, int duration) {
		for (int i = 0; i < Inventory.NUM_WORN_SLOTS; ++i) {
			ItemType t = player.inventory.wear[i];
			if (t == null) continue;
		
			ItemTraits_OnEquip equipEffects = t.effects_equip;
			if (equipEffects == null) continue;
			if (equipEffects.addedConditions == null) continue;
			for (ActorConditionEffect e : equipEffects.addedConditions) {
				if (!e.conditionType.conditionTypeID.equals(type.conditionTypeID)) continue;
				if (e.duration != duration) continue;
				// The player is wearing some other item that gives this condition. It will not be removed now.
				return;
			}
		}
		removeStackableActorCondition(player, type, magnitude, duration);
	}
	
	public static void applyActorCondition(Actor actor, ActorConditionEffect e) { applyActorCondition(actor, e, e.duration); }
	private static void applyActorCondition(Actor actor, ActorConditionEffect e, int duration) {
		if (e.isRemovalEffect()) {
			removeAllConditionsOfType(actor, e.conditionType.conditionTypeID);
		} else if (e.magnitude > 0) {
			if (e.conditionType.isStacking) {
				addStackableActorCondition(actor, e, duration);
			} else {
				addNonStackableActorCondition(actor, e, duration);
			}
		}
		recalculateActorCombatTraits(actor);
	}

	private static void addStackableActorCondition(Actor actor, ActorConditionEffect e, int duration) {
		final ActorConditionType type = e.conditionType;
		int magnitude = e.magnitude;
		
		for(int i = actor.conditions.size() - 1; i >= 0; --i) {
			ActorCondition c = actor.conditions.get(i);
			if (!type.conditionTypeID.equals(c.conditionType.conditionTypeID)) continue;
			if (c.duration == duration) {
				// If the actor already has a condition of this type and the same duration, just increase the magnitude instead.
				c.magnitude += magnitude;
				actor.conditionListener.onActorConditionMagnitudeChanged(actor, c);
				return;
			}
		}
		ActorCondition c = new ActorCondition(type, magnitude, duration);
		actor.conditions.add(c);
		actor.conditionListener.onActorConditionAdded(actor, c);
	}
	private static void addNonStackableActorCondition(Actor actor, ActorConditionEffect e, int duration) {
		final ActorConditionType type = e.conditionType;
		
		for(int i = actor.conditions.size() - 1; i >= 0; --i) {
			ActorCondition c = actor.conditions.get(i);
			if (!type.conditionTypeID.equals(c.conditionType.conditionTypeID)) continue;
			if (c.magnitude > e.magnitude) return;
			else if (c.magnitude == e.magnitude) {
				if (c.duration >= duration) return;
			}
			// If the actor already has this condition, but of a lower magnitude, we remove the old one and add this higher magnitude.
			actor.conditions.remove(i);
			actor.conditionListener.onActorConditionRemoved(actor, c);
		}
		
		ActorCondition c = e.createCondition(duration);
		actor.conditions.add(c);
		actor.conditionListener.onActorConditionAdded(actor, c);
	}

	public static void removeAllTemporaryConditions(final Actor actor) {
		for(int i = actor.conditions.size() - 1; i >= 0; --i) {
			ActorCondition c = actor.conditions.get(i);
			if (!c.isTemporaryEffect()) continue;
			actor.conditions.remove(i);
			actor.conditionListener.onActorConditionRemoved(actor, c);
		}
	}
	
	private static void removeAllConditionsOfType(final Actor actor, final String conditionTypeID) {
		for(int i = actor.conditions.size() - 1; i >= 0; --i) {
			ActorCondition c = actor.conditions.get(i);
			if (!c.conditionType.conditionTypeID.equals(conditionTypeID)) continue;
			actor.conditions.remove(i);
			actor.conditionListener.onActorConditionRemoved(actor, c);
		}
	}
	
	private static void applyEffectsFromCurrentConditions(Actor actor) {
		for (ActorCondition c : actor.conditions) {
			applyAbilityEffects(actor, c.conditionType.abilityEffect, c.magnitude);
		}
	}
	
	public static void applyAbilityEffects(Actor actor, AbilityModifierTraits effects, int multiplier) {
		if (effects == null) return;
		
		actor.health.addToMax(effects.increaseMaxHP * multiplier);
		actor.ap.addToMax(effects.increaseMaxAP * multiplier);
		actor.moveCost += effects.increaseMoveCost * multiplier;
		
		actor.attackCost += effects.increaseAttackCost * multiplier;
		//criticalMultiplier should not be increased. It is always defined by the weapon in use.
		actor.attackChance += effects.increaseAttackChance * multiplier;
		actor.criticalSkill += effects.increaseCriticalSkill * multiplier;
		actor.damagePotential.add(effects.increaseMinDamage * multiplier, true);
		actor.damagePotential.addToMax(effects.increaseMaxDamage * multiplier);
		actor.blockChance += effects.increaseBlockChance * multiplier;
		actor.damageResistance += effects.increaseDamageResistance * multiplier;
		
		if (actor.attackCost <= 0) actor.attackCost = 1;
		if (actor.attackChance < 0) actor.attackChance = 0;
		if (actor.moveCost <= 0) actor.moveCost = 1;
		if (actor.damagePotential.max < 0) actor.damagePotential.set(0, 0);
	}
	
	public static void recalculatePlayerStats(Player player) { 
		player.resetStatsToBaseTraits();
		player.recalculateLevelExperience();
		ItemController.applyInventoryEffects(player);
		SkillController.applySkillEffects(player);
		applyEffectsFromCurrentConditions(player);
		ItemController.recalculateHitEffectsFromWornItems(player);
		player.health.capAtMax();
		player.ap.capAtMax(); 
	}
	public static void recalculateMonsterCombatTraits(Monster monster) { 
		monster.resetStatsToBaseTraits();
		applyEffectsFromCurrentConditions(monster);
		monster.health.capAtMax();
		monster.ap.capAtMax(); 
	}
	private static void recalculateActorCombatTraits(Actor actor) {
		if (actor.isPlayer) recalculatePlayerStats((Player) actor);
		else recalculateMonsterCombatTraits((Monster) actor);
	}

	public void applyConditionsToPlayer(Player player, boolean isFullRound) {
		if (player.conditions.isEmpty()) return;
		if (!isFullRound) removeConditionsFromSkillEffects(player);
		
		applyStatsEffects(player, isFullRound);
		if (player.isDead()) {
			view.controller.handlePlayerDeath();
			return;
		}

		if (!isFullRound) decreaseDurationAndRemoveConditions(player);
		
		view.mainActivity.updateStatus();
	}

	private static void removeConditionsFromSkillEffects(Player player) {
		if (SkillController.rollForSkillChance(player, SkillCollection.SKILL_REJUVENATION, SkillCollection.PER_SKILLPOINT_INCREASE_REJUVENATION_CHANCE)) {
			int i = getRandomConditionForRejuvenate(player);
			if (i >= 0) {
				ActorCondition c = player.conditions.get(i);
				if (c.magnitude > 1) {
					c.magnitude -= 1;
					player.conditionListener.onActorConditionMagnitudeChanged(player, c);
				} else {
					player.conditions.remove(i);
					player.conditionListener.onActorConditionRemoved(player, c);
				}
				recalculatePlayerStats(player);
			}
		}
	}

	private static int getRandomConditionForRejuvenate(Player player) {
		ArrayList<Integer> potentialConditions = new ArrayList<Integer>();
		for(int i = 0; i < player.conditions.size(); ++i) {
			ActorCondition c = player.conditions.get(i);
			if (!c.isTemporaryEffect()) continue;
			if (c.conditionType.isPositive) continue;
			if (c.conditionType.conditionCategory == ActorConditionType.ACTORCONDITIONTYPE_SPIRITUAL) continue;
			potentialConditions.add(i);
		}
		if (potentialConditions.isEmpty()) return -1;
		
		return potentialConditions.get(Constants.rnd.nextInt(potentialConditions.size()));
	}

	public void applyConditionsToMonsters(PredefinedMap map, boolean isFullRound) {
		for (MonsterSpawnArea a : map.spawnAreas) {
			// Iterate the array backwards, since monsters may get removed from the array inside applyConditionsToMonster.
			for (int i = a.monsters.size()-1; i >= 0; --i) {
				final Monster m = a.monsters.get(i);
				applyConditionsToMonster(m, isFullRound);
			}
		}
	}

	private void applyConditionsToMonster(Monster monster, boolean isFullRound) {
		if (monster.conditions.isEmpty()) return;
		applyStatsEffects(monster, isFullRound);
		if (monster.isDead()) {
			view.combatController.playerKilledMonster(monster);
			return;
		}
		
		decreaseDurationAndRemoveConditions(monster);
	}

	private void applyStatsEffects(Actor actor, boolean isFullRound) {
		VisualEffect effectToStart = null;
		for (ActorCondition c : actor.conditions) {
			StatsModifierTraits effect = isFullRound ? c.conditionType.statsEffect_everyFullRound : c.conditionType.statsEffect_everyRound;
			effectToStart = applyStatsModifierEffect(actor, effect, c.magnitude, effectToStart);
			if (effect != null) actor.conditionListener.onActorConditionRoundEffectApplied(actor, c);
		}
		startVisualEffect(actor, effectToStart);
	}
	
	private static void decreaseDurationAndRemoveConditions(Actor actor) {
		boolean removedAnyConditions = false;
		for(int i = actor.conditions.size() - 1; i >= 0; --i) {
			ActorCondition c = actor.conditions.get(i);
			if (!c.isTemporaryEffect()) continue;
			if (c.duration <= 1) {
				actor.conditions.remove(i);
				actor.conditionListener.onActorConditionRemoved(actor, c);
				removedAnyConditions = true;
			} else {
				c.duration -= 1;
				actor.conditionListener.onActorConditionDurationChanged(actor, c);
			}
		}
		if (removedAnyConditions) {
			recalculateActorCombatTraits(actor);
		}
	}
	
	public void applyUseEffect(Actor source, Actor target, ItemTraits_OnUse effect) {
		if (effect == null) return;
		
		if (effect.addedConditions_source != null) {
			for (ActorConditionEffect e : effect.addedConditions_source) {
				rollForConditionEffect(source, e);
			}
		}
		if (target != null) {
			if (effect.addedConditions_target != null) {
				for (ActorConditionEffect e : effect.addedConditions_target) {
					rollForConditionEffect(target, e);
				}
			}
		}
		if (effect.changedStats != null) {
			VisualEffect effectToStart = applyStatsModifierEffect(source, effect.changedStats, 1, null);
			startVisualEffect(source, effectToStart);
		}
	}

	private static void rollForConditionEffect(Actor actor, ActorConditionEffect conditionEffect) {
		int chanceRollBias = 0;
		if (actor.isPlayer) chanceRollBias = SkillController.getActorConditionEffectChanceRollBias(conditionEffect, (Player) actor);
		
		if (!Constants.rollResult(conditionEffect.chance, chanceRollBias)) return;
		applyActorCondition(actor, conditionEffect);
	}

	private static class VisualEffect {
		public int visualEffectID;
		public int effectValue;
		public VisualEffect(int visualEffectID) {
			this.visualEffectID = visualEffectID;
		}
	}

	private void startVisualEffect(Actor actor, VisualEffect effectToStart) {
		if (effectToStart == null) return;
		view.effectController.startEffect(
			view.mainActivity.mainview
			, actor.position
			, effectToStart.visualEffectID
			, effectToStart.effectValue
			, null
			, 0);
	}
	
	private static VisualEffect applyStatsModifierEffect(Actor actor, StatsModifierTraits effect, int magnitude, VisualEffect existingVisualEffect) {
		if (effect == null) return existingVisualEffect;
		
		int effectValue = 0;
		int visualEffectID = effect.visualEffectID;
		if (effect.currentAPBoost != null) {
			effectValue = Constants.rollValue(effect.currentAPBoost);
			effectValue *= magnitude;
			boolean changed = actor.ap.change(effectValue, false, false);
			if (!changed) effectValue = 0; // So that the visualeffect doesn't start.
			if (effectValue != 0) {
				if (!effect.hasVisualEffect()) {
					visualEffectID = VisualEffectCollection.EFFECT_RESTORE_AP;
				}
			}
		}
		if (effect.currentHPBoost != null) {
			effectValue = Constants.rollValue(effect.currentHPBoost);
			effectValue *= magnitude;
			boolean changed = actor.health.change(effectValue, false, false);
			if (!changed) effectValue = 0; // So that the visualeffect doesn't start.
			if (effectValue != 0) {
				if (!effect.hasVisualEffect()) {
					if (effectValue > 0) {
						visualEffectID = VisualEffectCollection.EFFECT_RESTORE_HP;
					} else {
						visualEffectID = VisualEffectCollection.EFFECT_BLOOD;
					}
				}
			}
		}
		if (effectValue != 0) {
			if (existingVisualEffect == null) {
				existingVisualEffect = new VisualEffect(visualEffectID);
			} else if (Math.abs(effectValue) > Math.abs(existingVisualEffect.effectValue)) { 
				existingVisualEffect.visualEffectID = visualEffectID;
			}
			existingVisualEffect.effectValue += effectValue;
		}
		return existingVisualEffect;
	}
	
	public void applyKillEffectsToPlayer(Player player) {
		for (int i = 0; i < Inventory.NUM_WORN_SLOTS; ++i) {
			ItemType type = player.inventory.wear[i];
			if (type == null) continue;
			
			applyUseEffect(player, null, type.effects_kill);
		}
	}

	public void applySkillEffectsForNewRound(Player player, PredefinedMap currentMap) {
		int level = player.getSkillLevel(SkillCollection.SKILL_REGENERATION);
		if (level > 0) {
			boolean hasAdjacentMonster = MovementController.hasAdjacentAggressiveMonster(currentMap, player);
			if (!hasAdjacentMonster) {
				boolean changed = player.health.add(level * SkillCollection.PER_SKILLPOINT_INCREASE_REGENERATION, false);
				if (changed) view.mainActivity.updateStatus();
			}
		}
	}
}

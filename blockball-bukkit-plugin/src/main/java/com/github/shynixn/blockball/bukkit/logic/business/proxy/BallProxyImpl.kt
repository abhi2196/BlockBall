@file:Suppress("UNCHECKED_CAST")

package com.github.shynixn.blockball.bukkit.logic.business.proxy

import com.github.shynixn.blockball.api.BlockBallApi
import com.github.shynixn.blockball.api.bukkit.event.*
import com.github.shynixn.blockball.api.business.enumeration.BallSize
import com.github.shynixn.blockball.api.business.enumeration.MaterialType
import com.github.shynixn.blockball.api.business.proxy.BallProxy
import com.github.shynixn.blockball.api.business.proxy.EntityBallProxy
import com.github.shynixn.blockball.api.business.service.ConcurrencyService
import com.github.shynixn.blockball.api.business.service.ItemService
import com.github.shynixn.blockball.api.persistence.entity.BallMeta
import com.github.shynixn.blockball.api.persistence.entity.BounceConfiguration
import com.github.shynixn.blockball.bukkit.logic.business.extension.setSkin
import com.github.shynixn.blockball.core.logic.business.extension.cast
import com.github.shynixn.blockball.core.logic.business.extension.sync
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.entity.*
import org.bukkit.inventory.ItemStack
import org.bukkit.util.EulerAngle
import org.bukkit.util.Vector
import java.util.*
import java.util.logging.Level
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Created by Shynixn 2018.
 * <p>
 * Version 1.2
 * <p>
 * MIT License
 * <p>
 * Copyright (c) 2018 by Shynixn
 * <p>
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * <p>
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 * <p>
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
class BallProxyImpl(
    override val meta: BallMeta,
    private val design: LivingEntity,
    private val hitbox: LivingEntity,
    override val uuid: UUID = UUID.randomUUID(),
    private val initialOwner: LivingEntity?,
    override var persistent: Boolean
) : BallProxy, Runnable {

    companion object {
        private val itemService = BlockBallApi.resolve(ItemService::class.java)
        private val excludedRelativeItems = arrayOf(
            itemService.getMaterialFromMaterialType(MaterialType.OAK_FENCE),
            itemService.getMaterialFromMaterialType(MaterialType.IRON_BARS),
            itemService.getMaterialFromMaterialType(MaterialType.GLASS_PANE),
            itemService.getMaterialFromMaterialType(MaterialType.OAK_FENCE_GATE),
            itemService.getMaterialFromMaterialType(MaterialType.NETHER_FENCE),
            itemService.getMaterialFromMaterialType(MaterialType.COBBLESTONE_WALL),
            itemService.getMaterialFromMaterialType(MaterialType.STAINED_GLASS_PANE),
            org.bukkit.Material.SPRUCE_FENCE_GATE,
            org.bukkit.Material.BIRCH_FENCE_GATE,
            org.bukkit.Material.JUNGLE_FENCE_GATE,
            org.bukkit.Material.DARK_OAK_FENCE_GATE,
            org.bukkit.Material.ACACIA_FENCE_GATE,
            org.bukkit.Material.SPRUCE_FENCE,
            org.bukkit.Material.BIRCH_FENCE,
            org.bukkit.Material.JUNGLE_FENCE,
            org.bukkit.Material.DARK_OAK_FENCE,
            org.bukkit.Material.ACACIA_FENCE
        )
    }

    /** Design **/
    private val concurrencyService = BlockBallApi.resolve(ConcurrencyService::class.java)
    private var backAnimation = false
    private var interactionEntity: Entity? = null
    private var breakCounter = 20
    override var yawChange: Float = -1.0F

    /** HitBox **/
    private var knockBackBumper: Int = 0
    private var reduceVector: Vector? = null
    private var originVector: Vector? = null
    private var times: Int = 0

    /**
     * Current velocity of spin generating Magnus force.
     */
    override var angularVelocity: Double = 0.0

    /**
     * Is the ball currently grabbed by some entity?
     */
    override var isGrabbed: Boolean = false

    /**
     * Is the entity dead?
     */
    override val isDead: Boolean
        get() = this.design.isDead || this.hitbox.isDead

    init {
        design.customName = "ResourceBallsPlugin"
        design.isCustomNameVisible = false

        hitbox.customName = "ResourceBallsPlugin"
        hitbox.isCustomNameVisible = false

        /*
         * Y offset has to be more than 1.0, otherwise it sinks into the ground.
         * It does not apply to legacy versions below 1.10
         */
        this.teleport(this.getLocation<Location>().add(0.0, 1.0, 0.0))

        val event = BallSpawnEvent(this)
        Bukkit.getPluginManager().callEvent(event)
    }

    /**
     * Returns the armorstand for the design.
     */
    override fun <A> getDesignArmorstand(): A {
        return design as A
    }

    /**
     * Returns the hitbox entity.
     */
    override fun <A> getHitbox(): A {
        return hitbox as A
    }

    /**
     * When an object implementing interface `Runnable` is used
     * to create a thread, starting the thread causes the object's
     * `run` method to be called in that separately executing
     * thread.
     *
     *
     * The general contract of the method `run` is that it may
     * take any action whatsoever.
     *
     * @see java.lang.Thread.run
     */
    override fun run() {
        try {
            this.hitbox.fireTicks = 0
            this.design.fireTicks = 0

            if (!isGrabbed) {
                checkMovementInteractions()
                if (this.meta.rotating) {
                    this.playRotationAnimation()
                }
            } else {
                getSecondaryEntity<Entity>().teleport(getCalculationEntity<Entity>())
            }
        } catch (e: Exception) {
            Bukkit.getLogger().log(Level.WARNING, "Entity ticking exception.", e)
        }
    }

    /**
     * Removes the ball.
     */
    override fun remove() {
        Bukkit.getPluginManager().callEvent(BallDeathEvent(this))
        this.deGrab()

        this.design.remove()
        this.hitbox.remove()

        if (this.design is EntityBallProxy) {
            this.design.deleteFromWorld()
        }

        if (this.hitbox is EntityBallProxy) {
            this.hitbox.deleteFromWorld()
        }
    }


    /**
     * DeGrabs the ball.
     */
    override fun deGrab() {
        if (!this.isGrabbed || this.interactionEntity == null) {
            return
        }

        val livingEntity = this.interactionEntity as LivingEntity
        @Suppress("DEPRECATION", "UsePropertyAccessSyntax")
        livingEntity.equipment!!.setItemInHand(null)
        this.isGrabbed = false
        val itemStack = itemService.createItemStack<ItemStack>(MaterialType.SKULL_ITEM, 3)
        itemStack.setSkin(meta.skin)

        this.setHelmet(itemStack)
        val vector = this.getDirection(livingEntity).normalize().multiply(3)
        this.teleport(livingEntity.location.add(vector))
    }

    /**
     * Gets the velocity of the ball.
     */
    override fun <V> getVelocity(): V {
        return getCalculationEntity<Entity>().velocity as V
    }

    /**
     * Gets the last interaction entity.
     */
    override fun <L> getLastInteractionEntity(): Optional<L> {
        return Optional.ofNullable(interactionEntity as L)
    }

    /**
     * Kicks the ball by the given entity.
     * The calculated velocity can be manipulated by the BallKickEvent.
     *
     * @param entity entity
     */
    override fun <E> kickByEntity(entity: E) {
        if (entity !is Entity) {
            throw IllegalArgumentException("Entity has to be a BukkitEntity!")
        }

        if (this.isGrabbed) {
            return
        }

        val vector = entity.location.direction.normalize()
            .multiply(meta.movementModifier.horizontalKickModifier)
        this.yawChange = entity.location.yaw
        this.angularVelocity = 0.0
        vector.y = 0.1 * meta.movementModifier.verticalKickModifier

        val event = BallKickEvent(vector, entity, this)
        Bukkit.getPluginManager().callEvent(event)

        if (event.isCancelled) {
            return
        }

        this.setVelocity(vector)
        this.breakCounter = 2

        if (entity is Player) {
            sync(concurrencyService, 5L) {
                spin(entity.eyeLocation.direction, event.resultVelocity)
            }
        }
    }

    /**
     * Begins ball spin towards the player direction.
     */
    override fun <V> spin(playerDirection: V, resultVelocity: V) {
        if (playerDirection !is Vector) {
            throw IllegalArgumentException("PlayerDirection has to be a BukkitVelocity!")
        }

        if (resultVelocity !is Vector) {
            throw IllegalArgumentException("ResultVelocity has to be a BukkitVelocity!")
        }

        val angle = Math.toDegrees(getHorizontalDeviation(resultVelocity, playerDirection))
        val absAngle = abs(angle).toFloat()

        this.angularVelocity = when {
            absAngle < 30f -> return
            absAngle < 110f -> 0.08 * absAngle / 80
            else -> return
        }

        if (angle < 0.0) {
            this.angularVelocity *= -1f
        }
    }

    /**
     * Throws the ball by the given entity.
     * The calculated velocity can be manipulated by the BallThrowEvent.
     *
     * @param entity entity
     * @param entity entity
     */
    override fun <E> throwByEntity(entity: E) {
        if (entity !is Entity) {
            throw IllegalArgumentException("Entity has to be a BukkitEntity!")
        }

        if (interactionEntity == null || !this.isGrabbed || entity == interactionEntity) {
            return
        }

        this.deGrab()

        var vector = this.getDirection(entity).normalize()
        val y = vector.y
        vector = vector.multiply(meta.movementModifier.horizontalThrowModifier)
        vector.y = y * 2.0 * meta.movementModifier.verticalThrowModifier
        val event = BallThrowEvent(vector, entity, this)
        Bukkit.getPluginManager().callEvent(event)

        if (!event.isCancelled) {
            this.breakCounter = 2
            setVelocity(vector)
        }
    }

    /**
     * Teleports the ball to the given [location].
     */
    override fun <L> teleport(location: L) {
        if (location !is Location) {
            throw IllegalArgumentException("Location has to be a BukkitLocation!")
        }

        if (!this.isGrabbed) {
            this.getCalculationEntity<Entity>().teleport(location)
        }
    }

    /**
     * Gets the location of the ball.
     */
    override fun <L> getLocation(): L {
        return getCalculationEntity<Entity>().location as L
    }

    /**
     * Gets the optional living entity owner of the ball.
     */
    override fun <L> getOwner(): Optional<L> {
        return Optional.ofNullable(this.initialOwner as L)
    }

    /**
     * Sets the velocity of the ball.
     */
    override fun <V> setVelocity(vector: V) {
        if (vector !is Vector) {
            throw IllegalArgumentException("Vector has to be a BukkitVector!")
        }

        if (this.isGrabbed) {
            return
        }

        this.backAnimation = false
        this.angularVelocity = 0.0

        if (this.meta.rotating) {
            getDesignArmorstand<ArmorStand>().headPose = EulerAngle(2.0, 0.0, 0.0)
        }

        try {
            this.times = (50 * this.meta.movementModifier.rollingDistanceModifier).toInt()
            this.getCalculationEntity<Entity>().velocity = vector
            val normalized = vector.clone().normalize()
            this.originVector = vector.clone()
            this.reduceVector = Vector(normalized.x / this.times, 0.0784 * meta.movementModifier.gravityModifier, normalized.z / this.times)
        } catch (ignored: IllegalArgumentException) {
            // Ignore calculated velocity if it's out of range.
        }
    }

    /**
     * Lets the given living entity grab the ball.
     */
    override fun <L> grab(entity: L) {
        if (entity !is LivingEntity) {
            throw IllegalArgumentException("Entity has to be a BukkitLivingEntity!")
        }

        if (isGrabbed) {
            return
        }

        this.interactionEntity = entity

        @Suppress("DEPRECATION")
        if (entity.equipment!!.itemInHand.cast<ItemStack?>() == null || entity.equipment!!.itemInHand.type == Material.AIR) {
            val event = BallGrabEvent(entity, this)
            Bukkit.getPluginManager().callEvent(event)

            if (event.isCancelled) {
                return
            }

            @Suppress("UsePropertyAccessSyntax")
            entity.equipment!!.setItemInHand(getDesignArmorstand<ArmorStand>().helmet.clone())
            this.setHelmet(null)
            this.isGrabbed = true
        }
    }

    override fun <V> calculateMoveSourceVectors(movementVector: V, motionVector: V, onGround: Boolean): Optional<V> {
        if (movementVector !is Vector) {
            throw IllegalArgumentException("MovementVector has to be a BukkitVector!")
        }

        if (motionVector !is Vector) {
            throw IllegalArgumentException("MotionVector has to be a BukkitVector!")
        }

        val preMoveEvent = BallPreMoveEvent(movementVector, this)
        Bukkit.getPluginManager().callEvent(preMoveEvent)

        if (preMoveEvent.isCancelled) {
            return Optional.empty()
        }

        if (this.knockBackBumper > 0) {
            this.knockBackBumper--
        }

        return if ((this.times > 0 || !onGround) && this.originVector != null) {
            this.originVector = this.originVector!!.subtract(this.reduceVector!!)

            if (this.times > 0) {
                motionVector.x = this.originVector!!.x
                motionVector.z = this.originVector!!.z
            }

            motionVector.y = this.originVector!!.y
            this.times--

            Optional.of(Vector(motionVector.x, motionVector.y, motionVector.z) as V)
        } else {
            Optional.of(Vector(movementVector.x, movementVector.y, movementVector.z) as V)
        }
    }

    /**
     * Calculates the knockback for the given [sourceVector] and [sourceBlock]. Uses the motion values to correctly adjust the
     * wall.
     */
    override fun <V, B> calculateKnockBack(sourceVector: V, sourceBlock: B, mot0: Double, mot2: Double, mot6: Double, mot8: Double): Boolean {
        if (sourceVector !is Vector) {
            throw IllegalArgumentException("SourceVector has to be a BukkitVector!")
        }

        if (sourceBlock !is Block) {
            throw IllegalArgumentException("SourceBlock has to be a BukkitBlock!")
        }

        var knockBackBlock: Block = sourceBlock

        when {
            mot6 > mot0 -> {
                if (this.isValidKnockBackBlock(knockBackBlock)) {
                    knockBackBlock = knockBackBlock.getRelative(BlockFace.EAST)
                }

                val n = Vector(-1, 0, 0)
                return this.applyKnockBack(sourceVector, n, knockBackBlock, BlockFace.EAST)
            }
            mot6 < mot0 -> {
                if (this.isValidKnockBackBlock(knockBackBlock)) {
                    knockBackBlock = knockBackBlock.getRelative(BlockFace.WEST)
                }

                val n = Vector(1, 0, 0)
                return this.applyKnockBack(sourceVector, n, knockBackBlock, BlockFace.WEST)
            }
            mot8 > mot2 -> {
                if (this.isValidKnockBackBlock(knockBackBlock)) {
                    knockBackBlock = knockBackBlock.getRelative(BlockFace.SOUTH)
                }

                val n = Vector(0, 0, -1)
                return this.applyKnockBack(sourceVector, n, knockBackBlock, BlockFace.SOUTH)
            }
            mot8 < mot2 -> {
                if (this.isValidKnockBackBlock(knockBackBlock)) {
                    knockBackBlock = knockBackBlock.getRelative(BlockFace.NORTH)
                }

                val n = Vector(0, 0, 1)
                return this.applyKnockBack(sourceVector, n, knockBackBlock, BlockFace.NORTH)
            }
        }
        return false
    }

    /**
     * Calculates post movement.
     */
    override fun calculatePostMovement(collision: Boolean) {
        if (this.originVector == null) {
            return
        }

        // Movement calculation
        calculateSpinMovement(collision)

        val postMovement = BallPostMoveEvent(this.originVector!!, true, this)
        Bukkit.getPluginManager().callEvent(postMovement)
    }

    /**
     * Calculates spin movement. The spinning will slow down
     * if the ball stops moving, hits the ground or hits the wall.
     */
    private fun calculateSpinMovement(collision: Boolean) {
        if (abs(angularVelocity) < 0.01) {
            return
        }

        if (times <= 0 || getCalculationEntity<Entity>().isOnGround || collision) {
            angularVelocity /= 2
        }

        val event = BallSpinEvent(angularVelocity, this, false)
        Bukkit.getPluginManager().callEvent(event)

        if (!event.isCancelled) {
            angularVelocity = event.angularVelocity

            if (angularVelocity != 0.0) {
                val originUnit = this.originVector!!.clone().normalize()
                val x = -originUnit.z
                val z = originUnit.x
                val newVector = this.originVector!!.add(Vector(x, 0.0, z).multiply(angularVelocity.toFloat()))
                this.originVector = newVector.multiply(this.originVector!!.length() / newVector.length())
            }
        }
    }

    /**
     * Applies the wall knockback.
     *
     * @return whether the knockback was applied
     */
    private fun applyKnockBack(starter: Vector, n: Vector, block: Block, blockFace: BlockFace): Boolean {
        if (block.type == org.bukkit.Material.AIR && this.knockBackBumper <= 0) {
            val optBounce = getBounceConfigurationFromBlock(block)
            if (optBounce.isPresent || meta.alwaysBouce) {
                var r = starter.clone().subtract(n.multiply(2 * starter.dot(n))).multiply(0.75)

                r = if (optBounce.isPresent) {
                    r.multiply(optBounce.get().modifier)
                } else {
                    r.multiply(meta.movementModifier.defaultBounceModifier)
                }

                val event = BallWallCollideEvent(block, blockFace, starter.clone(), r.clone(), this)
                Bukkit.getPluginManager().callEvent(event)
                if (!event.isCancelled) {
                    this.setVelocity(r)
                    this.backAnimation = !backAnimation
                    this.knockBackBumper = 5
                    return true
                }
            }
        }
        return false
    }

    /**
     * Gets the bounce configuraiton for the given block.
     */
    private fun getBounceConfigurationFromBlock(block: Block): Optional<BounceConfiguration> {
        meta.bounceModifiers.forEach { modifier ->
            if (modifier.materialType == block.type) {
                @Suppress("DEPRECATION")
                if (modifier.materialDamage == block.data.toInt()) {
                    return Optional.of(modifier)
                }
            }
        }

        return Optional.empty()
    }

    /**
     * Checks movement interactions with the ball.
     */
    private fun checkMovementInteractions(): Boolean {
        if (this.breakCounter <= 0) {
            this.breakCounter = 2
            val ballLocation = getCalculationEntity<Entity>().location
            for (entity in ballLocation.chunk.entities) {
                if (entity.customName != "ResourceBallsPlugin" && entity.location.distance(ballLocation) < meta.hitBoxSize) {
                    val event = BallInteractEvent(entity, this)
                    Bukkit.getPluginManager().callEvent(event)
                    if (event.isCancelled)
                        return true
                    val vector = ballLocation
                        .toVector()
                        .subtract(entity.location.toVector())
                        .normalize().multiply(meta.movementModifier.horizontalTouchModifier)
                    vector.y = 0.1 * meta.movementModifier.verticalTouchModifier

                    this.yawChange = entity.location.yaw
                    this.setVelocity(vector)
                    return true
                }
            }
        } else {
            this.breakCounter--
        }
        return false
    }

    /**
     * Returns the launch Direction.
     *
     * @param entity entity
     * @return launchDirection
     */
    private fun getDirection(entity: Entity): Vector {
        val vector = Vector()
        val rotX = entity.location.yaw.toDouble()
        val rotY = entity.location.pitch.toDouble()
        vector.y = -sin(Math.toRadians(rotY))
        val h = cos(Math.toRadians(rotY))
        vector.x = -h * sin(Math.toRadians(rotX))
        vector.z = h * cos(Math.toRadians(rotX))
        vector.y = 0.5
        vector.add(entity.velocity)
        return vector.multiply(3)
    }

    /**
     * Plays the rotation animation.
     */
    private fun playRotationAnimation() {
        val length = getVelocity<Vector>().length()
        var angle: EulerAngle? = null

        val a = getDesignArmorstand<ArmorStand>().headPose
        when {
            length > 1.0 -> angle = if (this.backAnimation) {
                EulerAngle(a.x - 0.5, 0.0, 0.0)
            } else {
                EulerAngle(a.x + 0.5, 0.0, 0.0)
            }
            length > 0.1 -> angle = if (this.backAnimation) {
                EulerAngle(a.x - 0.25, 0.0, 0.0)
            } else {
                EulerAngle(a.x + 0.25, 0.0, 0.0)
            }
            length > 0.08 -> angle = if (this.backAnimation) {
                EulerAngle(a.x - 0.025, 0.0, 0.0)
            } else {
                EulerAngle(a.x + 0.025, 0.0, 0.0)
            }
        }
        if (angle != null) {
            getDesignArmorstand<ArmorStand>().headPose = angle
        }
    }

    /**
     * Sets the helmet.
     */
    private fun setHelmet(itemStack: ItemStack?) {
        when (meta.size) {
            BallSize.SMALL -> {
                getDesignArmorstand<ArmorStand>().isSmall = true
                getDesignArmorstand<ArmorStand>().setHelmet(itemStack)
            }
            BallSize.NORMAL -> getDesignArmorstand<ArmorStand>().setHelmet(itemStack)
        }
    }

    /**
     * Calculates the angle deviation between two vectors in X-Z dimension.
     * The angle never exceeds PI. If the calculated value is negative,
     * then subseq vector is actually not subsequent to precede vector.
     * @param subseq The vector subsequent to precede vector in clock-wised order.
     * @param precede The vector preceding subseq vector in clock-wised order.
     * @return A radian angle in the range of -PI to PI
     */
    private fun getHorizontalDeviation(subseq: Vector, precede: Vector): Double {
        val dot = subseq.x * precede.x + subseq.z * precede.z
        val det = subseq.x * precede.z - subseq.z * precede.x

        return atan2(det, dot)
    }

    /**
     * Gets if the given block is a valid knockback block.
     */
    private fun isValidKnockBackBlock(block: Block): Boolean {
        val material = block.type
        for (i in excludedRelativeItems) {
            if (i == material) {
                return false
            }
        }

        return true
    }

    /**
     * Gets the representative entity used for calculation.
     */
    private fun <A> getCalculationEntity(): A {
        return if (hitbox is Slime) {
            getDesignArmorstand()
        } else {
            getHitbox()
        }
    }

    /**
     * Gets the subordinate entity which is not used for calculation.
     */
    private fun <A> getSecondaryEntity(): A {
        return if (hitbox is Slime) {
            getHitbox()
        } else {
            getDesignArmorstand()
        }
    }
}
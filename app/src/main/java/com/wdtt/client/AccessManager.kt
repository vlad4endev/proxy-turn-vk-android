package com.wdtt.client

/**
 * Состояние доступа пользователя.
 *  - [Trial] — активен локальный пробный период (10 дней), [daysLeft] округляется вверх.
 *  - [Paid]  — активна платная подписка до [expireAt] (Unix-секунды).
 *  - [Expired] — ни триала, ни платной подписки.
 */
sealed interface AccessState {
    data class Trial(val daysLeft: Int, val expireAt: Long) : AccessState
    data class Paid(val expireAt: Long) : AccessState
    data object Expired : AccessState
}

/**
 * Единая точка правды о доступе. Приоритет: платная подписка ([SettingsStore.getSubExpireAt])
 * → локальный триал ([SettingsStore.getTrialStartAt]) → истёк.
 *
 * Бэкенд при выдаче «бессрочной» подписки должен выставлять expireAt далеко в будущее,
 * поэтому здесь достаточно сравнения expireAt > now.
 */
class AccessManager(private val store: SettingsStore) {

    private val trialSeconds: Long get() = BillingConfig.TRIAL_DAYS * SECONDS_PER_DAY

    /** Ставит trialStartAt при первом запуске (идемпотентно). */
    suspend fun ensureTrialStarted(nowSec: Long) {
        if (store.getTrialStartAt() == 0L) {
            store.saveTrialStartAt(nowSec)
        }
    }

    fun current(nowSec: Long): AccessState {
        val subExpire = store.getSubExpireAt()
        if (subExpire > nowSec) return AccessState.Paid(subExpire)

        val trialStart = store.getTrialStartAt()
        if (trialStart > 0L) {
            val trialEnd = trialStart + trialSeconds
            if (trialEnd > nowSec) {
                val daysLeft = ((trialEnd - nowSec + SECONDS_PER_DAY - 1) / SECONDS_PER_DAY).toInt()
                return AccessState.Trial(daysLeft.coerceAtLeast(0), trialEnd)
            }
        }
        return AccessState.Expired
    }

    /** «Маскировка» (VK→TURN→WG) — своя инфраструктура, доступна пока доступ не истёк. */
    fun isMaskingAllowed(nowSec: Long): Boolean = current(nowSec) !is AccessState.Expired

    /**
     * «Скорость» (VLESS/3X-UI) требует подписку: платно — всегда, в триале — только
     * при наличии общей триал-ссылки ([BillingConfig.TRIAL_SUB_URL]).
     */
    fun isSpeedAllowed(nowSec: Long): Boolean = when (current(nowSec)) {
        is AccessState.Paid -> true
        is AccessState.Trial -> BillingConfig.isTrialSpeedAvailable
        AccessState.Expired -> false
    }

    companion object {
        const val SECONDS_PER_DAY: Long = 86_400L
    }
}

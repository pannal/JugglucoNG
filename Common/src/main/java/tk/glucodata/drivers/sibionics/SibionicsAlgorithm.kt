package tk.glucodata.drivers.sibionics

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

// Constants fitted against a full-sensor-life golden trace of the vendor
// algorithm (libnative-algorithm v113B, sensor 46HU804E, 3152 comparison
// points): steady-state MARD 4.6% vs vendor output. See sibfit golden dataset.
private const val TEMP_POLY_REF_34_5C = 36.34948f
private const val TEMP_GAIN = 0.0353766f
private const val DEGRADATION_START_STAGE = 342.6381f
private const val DEGRADATION_RATE_PER_STAGE = 0.00125212f
private const val DEGRADATION_CAP = 2.7588152f
private const val ESA_ADJUSTED_FRACTION = 0.0419842f

enum class SibionicsAlgorithmMode {
    LIVE,
    REPLAY,
}

class SibionicsAlgorithmContext(
    private val sensorId: String,
) {
    private data class KalmanState(
        var estimate: Float = 0f,
        var previousEstimate: Float = 0f,
        var pMinus: Float = 0f,
        var gain: Float = 0f,
        var measurementNoise: Float = 1.2f,
        var processNoise: Float = 0.5f,
        var errorCovariance: Float = 0.0001f,
        var initialized: Boolean = false,
    ) {
        fun update(measurement: Float, index: Int): Float {
            if (!measurement.isFinite()) return estimate
            if (index < 5) {
                estimate = measurement
                previousEstimate = measurement
                initialized = true
                errorCovariance = 0.0001f
                return measurement
            }
            if (!initialized) {
                estimate = measurement
                previousEstimate = measurement
                initialized = true
                return estimate
            }
            pMinus = errorCovariance + processNoise
            gain = pMinus / (pMinus + measurementNoise)
            val next = estimate + gain * (measurement - estimate)
            previousEstimate = estimate
            estimate = next
            errorCovariance = pMinus * (1f - gain)
            return estimate
        }
    }

    private data class TemperatureState(
        val recent: ArrayDeque<Float> = ArrayDeque(),
        var lastTemperature: Float = 0f,
        var averageTemperature: Float = 0f,
    ) {
        fun update(value: Float, index: Int): Float {
            val sample = if (value >= 42f && lastTemperature > 0f) lastTemperature else value
            recent.addLast(sample)
            while (recent.size > 10) recent.removeFirst()
            lastTemperature = sample
            averageTemperature = if (index > 8 && recent.size == 10) {
                recent.sum() / recent.size
            } else {
                sample
            }
            return averageTemperature
        }
    }

    private data class CurrentCorrectionState(
        var olderRaw: Float = 0f,
        var previousRaw: Float = 0f,
        var currentRaw: Float = 0f,
        var previousCorrectedRaw: Float = 0f,
    ) {
        fun update(raw: Float, index: Int): Float {
            val older = previousRaw
            val previous = currentRaw
            olderRaw = older
            previousRaw = previous
            currentRaw = raw
            if (index < 1) {
                previousCorrectedRaw = raw
                return raw
            }
            val reference = max(if (previousCorrectedRaw == 0f) raw else previousCorrectedRaw, 1f)
            val threshold = reference * 10f
            val previousDelta = raw - previous
            val olderDelta = raw - older
            if (abs(previousDelta) <= threshold && abs(olderDelta) <= threshold) {
                previousCorrectedRaw = raw
                return raw
            }
            val corrected = when {
                previousDelta > threshold || olderDelta > threshold -> previous + 1f
                previousDelta <= -threshold || olderDelta <= -threshold -> {
                    if (abs(olderDelta) < 3f) {
                        currentRaw = older
                        older
                    } else {
                        previous - 1f
                    }
                }
                else -> raw
            }
            previousCorrectedRaw = corrected
            return corrected
        }
    }

    private data class CurrentJudgmentState(
        var previousCurrent: Float = 0f,
        var lastCurrent: Float = 0f,
        var currentFlag: Int = 0,
        var lastAbnormalCurrent: Float = 0f,
    ) {
        fun evaluate(current: Float, index: Int): Int {
            val diff = abs(previousCurrent - current)
            val mean = 0.5f * (lastCurrent + current)
            val variance = 0.5f * ((lastCurrent - mean).pow(2) + (current - mean).pow(2))
            val earlyHigh = index < 60 && current > 70f
            var nextFlag = if (earlyHigh) 1 else 0
            if (diff > 3.5f && variance > 1f && index >= 21 && nextFlag == 0) nextFlag = 1
            if (current < 1f && index > 59) nextFlag = 1
            if (
                nextFlag == 0 &&
                currentFlag == 1 &&
                lastAbnormalCurrent > 0f &&
                index >= 21 &&
                lastAbnormalCurrent < current
            ) {
                nextFlag = 1
            }
            if (nextFlag == 1) {
                lastAbnormalCurrent = current
            } else if (index <= 20 && current < 10f && lastAbnormalCurrent < current) {
                lastAbnormalCurrent = current
            }
            previousCurrent = current
            lastCurrent = current
            currentFlag = nextFlag
            return nextFlag
        }
    }

    private data class AbnormalJudgmentState(
        val current: CurrentJudgmentState = CurrentJudgmentState(),
        val recentCurrentFlags: ArrayDeque<Int> = ArrayDeque(),
        var zeroCurrentStreak: Int = 0,
    ) {
        fun evaluate(rawMmol: Float, temperatureC: Float, index: Int): Int {
            val currentFlag = current.evaluate(rawMmol, index)
            recentCurrentFlags.addLast(currentFlag)
            while (recentCurrentFlags.size > 14) recentCurrentFlags.removeFirst()
            zeroCurrentStreak = if (rawMmol == 0f) zeroCurrentStreak + 1 else 0
            var result = 0
            if (temperatureC < 10f) result = result or 0x01
            if (temperatureC > 42f) result = result or 0x02
            if (zeroCurrentStreak >= 2) result = result or 0x10
            if (recentCurrentFlags.count { it == 1 } >= 7) result = result or 0x20
            return result
        }
    }

    private data class FunctionFilterStage(
        val a1: Float,
        val a2: Float,
        val b0: Float,
        val b1: Float,
        val b2: Float,
        var y0: Float = 0f,
        var y1: Float = 0f,
        var x0: Float = 0f,
        var x1: Float = 0f,
    ) {
        fun update(input: Float, stageCount: Int): Float {
            if (stageCount <= 0) {
                y0 = input
                y1 = input
                x0 = input
                x1 = input
                return input
            }
            val y = b0 * input + b1 * x1 + b2 * x0 - a1 * y1 - a2 * y0
            x0 = x1
            x1 = input
            y0 = y1
            y1 = y
            return y
        }
    }

    private data class FilterOutputs(val f1: Float, val f2: Float, val f3: Float, val f4: Float, val f5: Float)

    private data class FunctionFilters(
        val stage1: FunctionFilterStage = FunctionFilterStage(1.5610181f, 0.6413515f, 0.8005924f, 1.6011848f, 0.8005924f),
        val stage2: FunctionFilterStage = FunctionFilterStage(-1.7786318f, 0.80080265f, 0.00554272f, 0.01108543f, 0.00554272f),
        val stage3: FunctionFilterStage = FunctionFilterStage(-1.9644606f, 0.96508116f, 0.00015515f, 0.0003103f, 0.00015515f),
        val stage4: FunctionFilterStage = FunctionFilterStage(-1.8226949f, 0.8371816f, 0.00362168f, 0.00724336f, 0.00362168f),
        val stage5: FunctionFilterStage = FunctionFilterStage(-1.5610181f, 0.6413515f, 0.02008337f, 0.04016673f, 0.02008337f),
    ) {
        fun update(input: Float, index: Int): FilterOutputs {
            val stageCount = max(index / 5 - 1, 0)
            return FilterOutputs(
                stage1.update(input, stageCount),
                stage2.update(input, stageCount),
                stage3.update(input, stageCount),
                stage4.update(input, stageCount),
                stage5.update(input, stageCount),
            )
        }
    }

    private data class ClippingFilterState(
        val recentValues: ArrayDeque<Float> = ArrayDeque(),
        val recentRates: ArrayDeque<Float> = ArrayDeque(),
        var stabilityCounter: Int = 0,
    ) {
        fun update(input: Float, filters: FilterOutputs, stageCount: Int): Float {
            val previousInput = recentValues.lastOrNull() ?: input
            recentValues.addLast(input)
            while (recentValues.size > 5) recentValues.removeFirst()
            val rate = abs(input - previousInput)
            recentRates.addLast(rate)
            while (recentRates.size > 5) recentRates.removeFirst()
            if (stageCount < 2) return input
            val avgRate = recentRates.sum() / recentRates.size
            stabilityCounter = if (avgRate < 0.15f) {
                min(stabilityCounter + 1, 100)
            } else {
                max(stabilityCounter - 2, 0)
            }
            return when {
                stabilityCounter >= 19 -> 0.3f * filters.f1 + 0.7f * filters.f5
                stabilityCounter >= 2 -> filters.f5
                avgRate < 0.5f -> {
                    val blend = min(avgRate / 0.5f, 1f)
                    filters.f5 * (1f - blend) + filters.f2 * blend
                }
                else -> 0.6f * filters.f2 + 0.4f * filters.f4
            }
        }
    }

    private data class DegradationTracker(var cumulativeBias: Float = 0f) {
        // Vendor drift is sensor-age driven: zero until ~28.6h of wear, then a
        // linear ramp to a late-life cap. Signature keeps adjusted/f3 so the
        // call site and stage shape stay unchanged.
        fun update(adjusted: Float, f3: Float, stageCount: Int): Float {
            val ageStage = stageCount.toFloat()
            cumulativeBias = if (ageStage <= DEGRADATION_START_STAGE) {
                0f
            } else {
                min((ageStage - DEGRADATION_START_STAGE) * DEGRADATION_RATE_PER_STAGE, DEGRADATION_CAP)
            }
            return cumulativeBias
        }
    }

    private data class EsaState(
        val previousTerms: FloatArray = FloatArray(4),
        var compensationSize: Float = 0f,
    ) {
        fun update(current: Float): Float {
            val average = (previousTerms.sum() + compensationSize) / 5f
            previousTerms[0] = previousTerms[1]
            previousTerms[1] = previousTerms[2]
            previousTerms[2] = previousTerms[3]
            previousTerms[3] = compensationSize
            return current + average
        }
    }

    private data class DeconvolutionState(val samples: ArrayDeque<Float> = ArrayDeque()) {
        private val coefficients = floatArrayOf(
            +2.1298696e-01f, -1.5606919e-02f, -1.16342455e-02f, -8.672798e-03f,
            -6.4651747e-03f, -4.8194914e-03f, -3.5927095e-03f, -2.6781994e-03f,
            -1.9964732e-03f, -1.4882768e-03f, -1.1094385e-03f, -8.2703045e-04f,
            -6.1650644e-04f, -4.5956802e-04f, -3.425744e-04f, -2.5535669e-04f,
            -1.903341e-04f, -1.4185499e-04f, -1.0570566e-04f, -7.874406e-05f,
            -5.862675e-05f, -4.3605167e-05f, -3.237366e-05f, -2.3956014e-05f,
            -1.762058e-05f, -1.2816693e-05f, -9.126808e-06f, -6.2302097e-06f,
            -3.8751286e-06f,
        )

        fun update(value: Float): Float {
            samples.addLast(value)
            while (samples.size > 29) samples.removeFirst()
            if (value < 3.5f || value > 15f || samples.size < 29) return value
            val list = samples.toList()
            var sum = 0.0
            for (i in coefficients.indices) {
                sum += coefficients[i] * list[28 - i]
            }
            val result = (sum * 6.7).toFloat()
            return if (result.isFinite() && result > 0f) result else value
        }
    }

    private var sensitivity = 1.27f
    private var shortCode = ""
    private var livePollDeltaMmol = Float.NaN
    private var replayPollDeltaMmol = Float.NaN
    private var liveBadValueStreak = 0
    private var replayBadValueStreak = 0
    private var abnormalJudgment = AbnormalJudgmentState()
    private var currentCorrection = CurrentCorrectionState()
    private var kalman = KalmanState()
    private var temperature = TemperatureState()
    private var filters = FunctionFilters()
    private var clipping = ClippingFilterState()
    private var degradation = DegradationTracker()
    private var esa = EsaState()
    private var deconvolution = DeconvolutionState()

    fun configure(shortCode: String, sensitivity: Float) {
        this.shortCode = shortCode
        this.sensitivity = sensitivity.coerceIn(0.8f, 2.5f)
    }

    fun reset() {
        livePollDeltaMmol = Float.NaN
        replayPollDeltaMmol = Float.NaN
        liveBadValueStreak = 0
        replayBadValueStreak = 0
        resetProcessingState()
    }

    fun displayUsingCurrentLiveDelta(rawMmol: Float): Float =
        if (livePollDeltaMmol.isFinite() && abs(livePollDeltaMmol) < 40f) {
            nativeRound(max(rawMmol + livePollDeltaMmol, 0f))
        } else {
            nativeRound(max(rawMmol, 0f))
        }

    fun process(rawMmol: Float, temperatureC: Float, index: Int, mode: SibionicsAlgorithmMode): Float {
        if (!rawMmol.isFinite() || rawMmol <= 0f) return Float.NaN
        val candidate = processCandidate(rawMmol, temperatureC, index)
        val display = when (mode) {
            SibionicsAlgorithmMode.LIVE -> {
                if (candidate != null && candidate > 1f) {
                    livePollDeltaMmol = candidate - rawMmol
                    candidate
                } else {
                    val fallbackDelta = when {
                        livePollDeltaMmol.isFinite() && abs(livePollDeltaMmol) < 40f -> livePollDeltaMmol
                        replayPollDeltaMmol.isFinite() && abs(replayPollDeltaMmol) < 40f -> replayPollDeltaMmol
                        else -> Float.NaN
                    }
                    if (fallbackDelta.isFinite()) {
                        livePollDeltaMmol = fallbackDelta
                        nativeRound(rawMmol + fallbackDelta)
                    } else {
                        rawMmol
                    }
                }
            }
            SibionicsAlgorithmMode.REPLAY -> {
                if (candidate != null && candidate > 1f) {
                    replayPollDeltaMmol = candidate - rawMmol
                    candidate
                } else if (replayPollDeltaMmol.isFinite() && abs(replayPollDeltaMmol) < 40f) {
                    nativeRound(rawMmol + replayPollDeltaMmol)
                } else {
                    rawMmol
                }
            }
        }
        val invalidHigh = display > 50f
        if (invalidHigh) {
            if (mode == SibionicsAlgorithmMode.LIVE) liveBadValueStreak++ else replayBadValueStreak++
            if (liveBadValueStreak >= 5 || replayBadValueStreak >= 5) {
                resetProcessingState()
                livePollDeltaMmol = Float.NaN
                replayPollDeltaMmol = Float.NaN
                liveBadValueStreak = 0
                replayBadValueStreak = 0
            }
            return rawMmol
        }
        if (mode == SibionicsAlgorithmMode.LIVE) liveBadValueStreak = 0 else replayBadValueStreak = 0
        return max(display, 0f)
    }

    private fun resetProcessingState() {
        abnormalJudgment = AbnormalJudgmentState()
        currentCorrection = CurrentCorrectionState()
        kalman = KalmanState()
        temperature = TemperatureState()
        filters = FunctionFilters()
        clipping = ClippingFilterState()
        degradation = DegradationTracker()
        esa = EsaState()
        deconvolution = DeconvolutionState()
    }

    private fun processCandidate(rawMmol: Float, temperatureC: Float, index: Int): Float? {
        val indexMinusOne = max(index - 1, 0)
        val abnormalFlags = abnormalJudgment.evaluate(rawMmol, temperatureC, indexMinusOne)
        val corrected = currentCorrection.update(rawMmol, indexMinusOne)
        if ((abnormalFlags and 0x30) != 0) return null

        val filtered = kalman.update(corrected, index)
        val compensated = temperatureCompensated(filtered, temperatureC, index)
        if (index % 5 != 0) return null

        val s27 = adjustment(temperatureC, index)
        val adjusted = (compensated - s27) / sensitivity.coerceAtLeast(0.8f)
        val out = filters.update(adjusted, index)
        val stageCount = max(index / 5 - 1, 0)
        clipping.update(adjusted, out, stageCount)
        val degradationComp = degradation.update(adjusted, out.f3, stageCount)
        val signalDependentComp = max(0f, degradationComp - ESA_ADJUSTED_FRACTION * max(adjusted, 0f))
        esa.compensationSize = signalDependentComp
        val esaResult = esa.update(adjusted)
        val deconvolved = deconvolution.update(esaResult)
        return nativeRound(max(deconvolved, 0f))
    }

    private fun temperaturePolynomial(t: Float): Float {
        val d = t.toDouble()
        return (
            65.21018525 +
                (-0.037863630801439285 * d) +
                (-0.7637396454811096 * d.pow(2.0)) +
                (0.061271119862794876 * d.pow(3.0)) +
                (-0.0019132299348711967 * d.pow(4.0)) +
                (0.00002677628435776569 * d.pow(5.0)) +
                (-0.00000013804908860493015 * d.pow(6.0))
            ).toFloat()
    }

    private fun temperatureCompensated(rawMmol: Float, temperatureC: Float, index: Int): Float {
        val temp = temperature.update(temperatureC, index)
        val poly = temperaturePolynomial(temp)
        // Reference-normalized: zero at 34.5°C, lifts cold readings, damps warm ones.
        val correction = (rawMmol - 0.5f) * (TEMP_POLY_REF_34_5C - poly) * TEMP_GAIN
        return rawMmol + correction
    }

    private fun adjustment(temperatureC: Float, index: Int): Float =
        if (index <= 605) 0.2f * (temperatureC - 34.5f) + 0.3f else 0.5f

    private fun nativeRound(value: Float): Float {
        if (!value.isFinite()) return Float.NaN
        val scaled = value * 10f
        val rounded = if (value >= 0f) scaled + 0.5f else scaled
        return rounded.toInt() / 10f
    }
}

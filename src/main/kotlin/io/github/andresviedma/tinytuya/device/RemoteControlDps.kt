package io.github.andresviedma.tinytuya.device

/**
 * DPS and nested-send-payload constants shared by all Tuya remote control devices
 * (IR and RF).
 *
 * Two hardware generations exist:
 *  - Control type 1 (older): uses DPS 201 to send commands and DPS 202 to receive learned codes.
 *  - Control type 2 (newer): uses DPS 1–13.
 */
object RemoteControlDps {

    // ── Control-type 1 DPS (older devices, DPS 201/202) ───────────────────────
    const val DP_SEND_IR        = "201"
    const val DP_LEARNED_ID     = "202"

    // ── Control-type 2 DPS (newer devices, DPS 1–13) ─────────────────────────
    const val DP_MODE           =   "1"
    const val DP_LEARNED_REPORT =   "2"
    const val DP_HEAD           =   "3"
    const val DP_KEY_CODE       =   "4"
    const val DP_KEY_CODE2      =   "5"
    const val DP_KEY_CODE3      =   "6"
    const val DP_KEY_CODE4      =  "11"
    const val DP_KEY_STUDY      =   "7"
    const val DP_KEY_STUDY2     =   "8"
    const val DP_KEY_STUDY3     =   "9"
    const val DP_KEY_STUDY4     =  "12"
    const val DP_SEND_DELAY     =  "10"
    const val DP_CODE_TYPE      =  "13"

    // ── Nested-send DPS field names (keys inside the JSON written to DP_SEND_IR)
    const val NSDP_CONTROL    = "control"
    const val NSDP_STUDY_CODE = "study_code"
    const val NSDP_IR_CODE    = "ir_code"
    const val NSDP_KEY_CODE   = "key_code"
    const val NSDP_KEY_CODE2  = "key_code2"
    const val NSDP_KEY_CODE3  = "key_code3"
    const val NSDP_KEY_CODE4  = "key_code4"
    const val NSDP_KEY_STUDY  = "key_study"
    const val NSDP_KEY_STUDY2 = "key_study2"
    const val NSDP_KEY_STUDY3 = "key_study3"
    const val NSDP_KEY_STUDY4 = "key_study4"
    const val NSDP_DELAY_TIME = "delay_time"
    const val NSDP_TYPE       = "type"
    const val NSDP_DELAY      = "delay"
    const val NSDP_HEAD       = "head"
    const val NSDP_KEY1       = "key1"
}

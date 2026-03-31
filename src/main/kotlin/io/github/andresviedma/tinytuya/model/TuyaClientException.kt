package io.github.andresviedma.tinytuya.model

import io.github.andresviedma.tinytuya.protocol.TuyaMessage

class TuyaClientException(val response: TuyaMessage)
    : Exception("Error response received with status ${response.returnCode} -- ${response.payloadText}")

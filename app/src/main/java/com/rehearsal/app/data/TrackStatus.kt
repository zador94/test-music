package com.rehearsal.app.data

object TrackStatus {
    const val QUEUED = "queued"
    const val PROCESSING = "processing"
    const val READY = "ready"
    const val ERROR = "error"
}

object SplitKind {
    /** HT-Demucs, один ONNX, 4 выхода: drums, bass, other, vocals. */
    const val HTDEMUCS_4STEM = "htdemucs_onnx_4stem"

    const val MID_SIDE_2STEM_NOT_ML = "mid_side_2stem_not_ml"
    const val MONO_DUPLICATE_STUB = "mono_duplicate_stub"
}

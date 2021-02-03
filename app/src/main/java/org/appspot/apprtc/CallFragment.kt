/*
 *  Copyright 2015 The WebRTC Project Authors. All rights reserved.
 *
 *  Use of this source code is governed by a BSD-style license
 *  that can be found in the LICENSE file in the root of the source
 *  tree. An additional intellectual property rights grant can be found
 *  in the file PATENTS.  All contributing project authors may
 *  be found in the AUTHORS file in the root of the source tree.
 */
package org.appspot.apprtc

import android.app.Activity
//import android.app.Fragment
import androidx.fragment.app.Fragment
import android.os.Bundle
import android.view.*
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import org.webrtc.RendererCommon.ScalingType

/**
 * Fragment for call control.
 */
class CallFragment : Fragment() {
    private var contactView: TextView? = null
    private var cameraSwitchButton: ImageButton? = null
    private var videoScalingButton: ImageButton? = null
    private var toggleMuteButton: ImageButton? = null
    private var captureFormatText: TextView? = null
    private var captureFormatSlider: SeekBar? = null
    private var callEvents: OnCallEvents? = null
    private var scalingType: ScalingType? = null
    private var videoCallEnabled = true

    /**
     * Call control interface for container activity.
     */
    interface OnCallEvents {
        fun onCallHangUp()
        fun onCameraSwitch()
        fun onVideoScalingSwitch(scalingType: ScalingType?)
        fun onCaptureFormatChange(width: Int, height: Int, framerate: Int)
        fun onToggleMic(): Boolean
    }

    override fun onCreateView(
            inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val controlView = inflater.inflate(R.layout.fragment_call, container, false)

        // Create UI controls.
        contactView = controlView.findViewById<View>(R.id.contact_name_call) as TextView
        val disconnectButton = controlView.findViewById<View>(R.id.button_call_disconnect) as ImageButton
        cameraSwitchButton = controlView.findViewById<View>(R.id.button_call_switch_camera) as ImageButton
        videoScalingButton = controlView.findViewById<View>(R.id.button_call_scaling_mode) as ImageButton
        toggleMuteButton = controlView.findViewById<View>(R.id.button_call_toggle_mic) as ImageButton
        captureFormatText = controlView.findViewById<View>(R.id.capture_format_text_call) as TextView
        captureFormatSlider = controlView.findViewById<View>(R.id.capture_format_slider_call) as SeekBar

        // Add buttons click events.
        disconnectButton.setOnClickListener { callEvents!!.onCallHangUp() }
        cameraSwitchButton!!.setOnClickListener { callEvents!!.onCameraSwitch() }
        videoScalingButton!!.setOnClickListener {
            scalingType = if (scalingType == ScalingType.SCALE_ASPECT_FILL) {
                videoScalingButton!!.setBackgroundResource(R.drawable.ic_action_full_screen)
                ScalingType.SCALE_ASPECT_FIT
            } else {
                videoScalingButton!!.setBackgroundResource(R.drawable.ic_action_return_from_full_screen)
                ScalingType.SCALE_ASPECT_FILL
            }
            callEvents!!.onVideoScalingSwitch(scalingType)
        }
        scalingType = ScalingType.SCALE_ASPECT_FILL
        toggleMuteButton!!.setOnClickListener {
            val enabled = callEvents!!.onToggleMic()
            toggleMuteButton!!.alpha = if (enabled) 1.0f else 0.3f
        }
        return controlView
    }

    override fun onStart() {
        super.onStart()
        var captureSliderEnabled = false
        val args = arguments
        if (args != null) {
            val contactName = args.getString(CallActivity.Companion.EXTRA_ROOMID)
            contactView!!.text = contactName
            videoCallEnabled = args.getBoolean(CallActivity.Companion.EXTRA_VIDEO_CALL, true)
            captureSliderEnabled = (videoCallEnabled
                    && args.getBoolean(CallActivity.Companion.EXTRA_VIDEO_CAPTUREQUALITYSLIDER_ENABLED, false))
        }
        if (!videoCallEnabled) {
            cameraSwitchButton!!.visibility = View.INVISIBLE
        }
        if (captureSliderEnabled) {
            captureFormatSlider!!.setOnSeekBarChangeListener(
                    CaptureQualityController(captureFormatText, callEvents))
        } else {
            captureFormatText!!.visibility = View.GONE
            captureFormatSlider!!.visibility = View.GONE
        }
    }

    // TODO(sakal): Replace with onAttach(Context) once we only support API level 23+.
    override fun onAttach(activity: Activity) {
        super.onAttach(activity)
        callEvents = activity as OnCallEvents
    }
}
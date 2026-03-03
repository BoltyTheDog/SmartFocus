package com.example.msdksample

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import androidx.fragment.app.DialogFragment
import com.example.msdksample.databinding.DialogSettingsBinding
import org.tensorflow.lite.examples.objectdetection.ObjectDetectorHelper

class SettingsDialogFragment : DialogFragment() {

    private var _binding: DialogSettingsBinding? = null
    private val binding get() = _binding!!

    interface SettingsChangeListener {
        fun onSettingsChanged(
            threshold: Float,
            maxResults: Int,
            numThreads: Int,
            delegate: Int,
            targetIp: String,
            targetClasses: String
        )
    }

    private var listener: SettingsChangeListener? = null

    companion object {
        fun newInstance(
            threshold: Float,
            maxResults: Int,
            numThreads: Int,
            delegate: Int,
            targetIp: String,
            targetClasses: String
        ): SettingsDialogFragment {
            val fragment = SettingsDialogFragment()
            val args = Bundle()
            args.putFloat("threshold", threshold)
            args.putInt("maxResults", maxResults)
            args.putInt("numThreads", numThreads)
            args.putInt("delegate", delegate)
            args.putString("targetIp", targetIp)
            args.putString("targetClasses", targetClasses)
            fragment.arguments = args
            return fragment
        }
    }

    fun setSettingsChangeListener(listener: SettingsChangeListener) {
        this.listener = listener
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val args = arguments ?: return
        var threshold = args.getFloat("threshold")
        var maxResults = args.getInt("maxResults")
        var numThreads = args.getInt("numThreads")
        var delegate = args.getInt("delegate")
        val targetIp = args.getString("targetIp", "")
        val targetClasses = args.getString("targetClasses", "")

        binding.thresholdValue.text = String.format("%.2f", threshold)
        binding.maxResultsValue.text = maxResults.toString()
        binding.threadsValue.text = numThreads.toString()
        binding.editTargetIp.setText(targetIp)
        binding.editTargetClasses.setText(targetClasses)
        binding.spinnerDelegate.setSelection(delegate)

        binding.thresholdMinus.setOnClickListener {
            if (threshold >= 0.1) {
                threshold -= 0.1f
                binding.thresholdValue.text = String.format("%.2f", threshold)
            }
        }

        binding.thresholdPlus.setOnClickListener {
            if (threshold <= 0.8) {
                threshold += 0.1f
                binding.thresholdValue.text = String.format("%.2f", threshold)
            }
        }

        binding.maxResultsMinus.setOnClickListener {
            if (maxResults > 1) {
                maxResults--
                binding.maxResultsValue.text = maxResults.toString()
            }
        }

        binding.maxResultsPlus.setOnClickListener {
            if (maxResults < 5) {
                maxResults++
                binding.maxResultsValue.text = maxResults.toString()
            }
        }

        binding.threadsMinus.setOnClickListener {
            if (numThreads > 1) {
                numThreads--
                binding.threadsValue.text = numThreads.toString()
            }
        }

        binding.threadsPlus.setOnClickListener {
            if (numThreads < 4) {
                numThreads++
                binding.threadsValue.text = numThreads.toString()
            }
        }

        binding.spinnerDelegate.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                delegate = position
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        binding.btnClose.setOnClickListener {
            listener?.onSettingsChanged(
                threshold,
                maxResults,
                numThreads,
                delegate,
                binding.editTargetIp.text.toString(),
                binding.editTargetClasses.text.toString()
            )
            dismiss()
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(
                (resources.displayMetrics.widthPixels * 0.9).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

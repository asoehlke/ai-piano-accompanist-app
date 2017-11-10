/*
 *    Copyright (C) 2017 Andreas Soehlke
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package com.asoehlke.accompanist;

import android.content.res.AssetManager;
import android.os.Trace;
import android.util.Log;

import org.tensorflow.contrib.android.TensorFlowInferenceInterface;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Vector;

/**
 * A classifier specialized to label images using TensorFlow.
 */
public class TensorFlowAccompanist {

    public class Note {
        /**
         * MIDI key number of the note.
         */
        private final int key;

        /**
         * True, if the key is struck, false, if it is sustained
         */
        private final boolean strike;

        public Note(
                final int key, final boolean strike) {
            this.key = key;
            this.strike = strike;
        }
    }

    private static final String TAG = "TensorFlowAccompanist";

    // Threshold for interpreting the attack output
    private static final float ATTACK_THRESHOLD = 0.5f;
    // number of voices
    private static final int NUM_VOICES = 4;

    // number of output classes, i.e. half tone differences to melody
    private static final int NUM_CLASSES = 26;

    // duration of time tick in beats. 1=quarter, 0.25=16th
    private static final float TIME_TICK = 0.25f;


    // Config values.
    private String inputName;
    private String outputName;
    private int inputSize;

    // Pre-allocated buffers.
    private int[] intValues;
    private float[] floatValues;
    private float[] outputs;

    private TensorFlowInferenceInterface inferenceInterface;

    private TensorFlowAccompanist() {
    }

    /**
     * Initializes a native TensorFlow session for classifying images.
     *
     * @param assetManager  The asset manager to be used to load assets.
     * @param modelFilename The filepath of the model GraphDef protocol buffer.
     * @param inputSize     The input size. A square image of inputSize x inputSize is assumed.
     * @param inputName     The label of the image input node.
     * @param outputName    The label of the output node.
     * @throws IOException
     */
    public static TensorFlowAccompanist create(
            AssetManager assetManager,
            String modelFilename,
            int inputSize,
            String inputName,
            String outputName)
            throws IOException {
        TensorFlowAccompanist accompanist = new TensorFlowAccompanist();

        accompanist.inferenceInterface = new TensorFlowInferenceInterface(assetManager, modelFilename);
        if (accompanist.inferenceInterface.initializeTensorFlow() != 0) {
            throw new RuntimeException("TF initialization failed");
        }
        // The shape of the output is [N, NUM_CLASSES], where N is the batch size.
        int numClasses =
                (int) c.inferenceInterface.graph().operation(outputName).output(0).shape().size(1);
        Log.i(TAG, "Read " + c.labels.size() + " labels, output layer size is " + numClasses);

        // Ideally, inputSize could have been retrieved from the shape of the input operation.  Alas,
        // the placeholder node for input in the graphdef typically used does not specify a shape, so it
        // must be passed in as a parameter.
        accompanist.inputSize = inputSize;

        // Pre-allocate buffers.
        accompanist.intValues = new int[inputSize * inputSize];
        accompanist.floatValues = new float[inputSize * inputSize * 3];
        accompanist.outputs = new float[numClasses];

        return accompanist;
    }

    @Override
    public List<Note> getNextVoices(final Note melodyNote) {
        Trace.beginSection("getNextVoices");

        // Copy the input data into TensorFlow.
        Trace.beginSection("fillInput");
        inferenceInterface.fillNodeFloat(
                inputName, new int[]{1, inputSize, inputSize, 3}, floatValues);
        Trace.endSection();

        // Run the inference call.
        Trace.beginSection("runInference");
        inferenceInterface.runInference(outputNames);
        Trace.endSection();

        // Copy the output Tensor back into the output array.
        Trace.beginSection("readNodeFloat");
        inferenceInterface.readNodeFloat(outputName, outputs);
        Trace.endSection();

        // Find the best classifications.
        PriorityQueue<Recognition> pq =
                new PriorityQueue<Recognition>(
                        3,
                        new Comparator<Recognition>() {
                            @Override
                            public int compare(Recognition lhs, Recognition rhs) {
                                // Intentionally reversed to put high confidence at the head of the queue.
                                return Float.compare(rhs.getConfidence(), lhs.getConfidence());
                            }
                        });
        for (int i = 0; i < outputs.length; ++i) {
            if (outputs[i] > THRESHOLD) {
                pq.add(
                        new Recognition(
                                "" + i, labels.size() > i ? labels.get(i) : "unknown", outputs[i], null));
            }
        }
        final ArrayList<Recognition> recognitions = new ArrayList<Recognition>();
        int recognitionsSize = Math.min(pq.size(), MAX_RESULTS);
        for (int i = 0; i < recognitionsSize; ++i) {
            recognitions.add(pq.poll());
        }
        Trace.endSection(); // "recognizeImage"
        return recognitions;
    }

    @Override
    public void enableStatLogging(boolean debug) {
        inferenceInterface.enableStatLogging(debug);
    }

    @Override
    public String getStatString() {
        return inferenceInterface.getStatString();
    }

    @Override
    public void close() {
        inferenceInterface.close();
    }
}

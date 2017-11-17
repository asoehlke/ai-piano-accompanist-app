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
import android.os.Build;
import android.os.Trace;
import android.util.Log;

import org.tensorflow.Graph;
import org.tensorflow.Session;
import org.tensorflow.contrib.android.TensorFlowInferenceInterface;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Uses a TensorFlow model to create accompanying voices to a melody
 */
public class TensorFlowAccompanist {

    public static class Note {
        /**
         * MIDI key number of the note.
         */
        private final int key;

        /**
         * True, if the key is struck, false, if it is sustained
         */
        private final boolean strike;

        public Note(final int key, final boolean strike)
        {
            this.key = key;
            this.strike = strike;
        }

        public int getKey() { return key; }

        public boolean getStrike() {return strike;}

        public String toString() {
            return "Note=" + key + " strike=" + strike;
        }
    }

    private static final String TAG = "TensorFlowAccompanist";

    // Threshold for interpreting the attack output
    private static final float STRIKE_THRESHOLD = 0.5f;

    // duration of time tick in beats. 1=quarter, 0.25=16th
    private static final float TIME_TICK = 0.25f;

    private static final int NUM_VOICES = 4;


    // Config values: input and output network node names
    private String inputName;
    private String outputName;

    // name of the operation to copy the network states of the last not as input for the next
    private String copyStatesName;

    private int inputSize;
    private int numAccompanyingVoices;

    // Pre-allocated buffers.
    private int[] intValues;
    private float[] floatValues;
    private float[] outputs;

    private Graph g;
    private Session sess;
    private Session.Runner runner;

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
     */
    public static TensorFlowAccompanist create(
            AssetManager assetManager,
            String modelFilename,
            int inputSize,
            String inputName,
            String outputName,
            String copyStatesName
            )
    {
        TensorFlowAccompanist accompanist = new TensorFlowAccompanist();

        Log.i(TAG, "opening model " + modelFilename);

        accompanist.inferenceInterface =
                new TensorFlowInferenceInterface(assetManager, modelFilename);

        // The shape of the output is [(NUM_VOICES - 1) * 2],
        // a note for each accompaying voice followed by the strike probability
        //accompanist.numAccompanyingVoices =
        //        (int) accompanist.inferenceInterface.graph().operation(outputName).output(0).shape().size(1) / 2;
        accompanist.numAccompanyingVoices = NUM_VOICES - 1;
        Log.i(TAG, "number of output voices: " + accompanist.numAccompanyingVoices);

        // Ideally, inputSize could have been retrieved from the shape of the input operation.  Alas,
        // the placeholder node for input in the graphdef typically used does not specify a shape, so it
        // must be passed in as a parameter.
        accompanist.inputSize = inputSize;

        // Pre-allocate buffers.
        accompanist.intValues = new int[inputSize];
        accompanist.floatValues = new float[inputSize];
        accompanist.outputs = new float[accompanist.numAccompanyingVoices * 2];

        accompanist.inputName = inputName;
        accompanist.outputName = outputName;
        accompanist.copyStatesName = copyStatesName;

        accompanist.g = accompanist.inferenceInterface.graph();
        accompanist.sess = new Session(accompanist.g);
        accompanist.runner = accompanist.sess.runner();

        // call the initializing operations
        accompanist.runner.addTarget("init");
        Log.i(TAG, "init: " + accompanist.runner.run());
        accompanist.runner.addTarget("initVars");
        Log.i(TAG, "initVars: " + accompanist.runner.run());
        accompanist.runner.addTarget("init");
        Log.i(TAG, "init + meta: " + accompanist.runner.runAndFetchMetadata());
        accompanist.runner.addTarget("initVars");
        Log.i(TAG, "initVars + meta: " + accompanist.runner.runAndFetchMetadata());

        //accompanist.inferenceInterface =
        //        new TensorFlowInferenceInterface(accompanist.g);
        // initialize all variables, otherwise some the initial state variables
        // seem not to be initialized.
        //accompanist.inferenceInterface.run(new String[] {"initVars:0"});
        //accompanist.runner.addTarget(copyStatesName);
        //accompanist.runner.run();



        return accompanist;
    }

    public List<Note> getNextVoices(final Note melodyNote) {
        //Trace.beginSection("getNextVoices");

        // Copy the input data into TensorFlow.
        //Trace.beginSection("fillInput");
        intValues[0] = melodyNote.getKey();
        if (melodyNote.getStrike()) intValues[1] = 1;
        else intValues[1] = 0;

        Log.i(TAG, "melody note: " + melodyNote);

        inferenceInterface.feed(inputName, intValues, 2);
        //Trace.endSection();

        // Run the inference call.
        //Trace.beginSection("runInference");

        // determine the output
        //runner.addTarget(outputName);
        //runner.run();
        inferenceInterface.run(new String[] {outputName});
        //Trace.endSection();

        // Copy the output Tensor back into the output array.
        //Trace.beginSection("readOutput");1
        inferenceInterface.fetch(outputName, outputs);
        //Trace.endSection();

        // copy the rnn cell states to the input of the next call
        runner.addTarget(copyStatesName);
        runner.run();
        //inferenceInterface.run(new String[] {copyStatesName});

        final ArrayList<Note> notes = new ArrayList<Note>();
        for (int i = 0; i < numAccompanyingVoices; ++i) {
            boolean strike;
            strike = outputs[i] > STRIKE_THRESHOLD;
            notes.add(new Note(Math.round(outputs[i]), strike));
            Log.i(TAG, "voice: " + (i + 1) + ": " + notes.get(notes.size() - 1));

        }
        //Trace.endSection();
        return notes;
    }

    public void close() {
        inferenceInterface.close();
    }
}

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

import com.asoehlke.accompanist.TensorFlowAccompanist.Note;

import android.os.Handler;

import android.content.res.AssetManager;
import android.util.Log;

import com.levien.synthesizer.core.midi.MidiListener;
import com.levien.synthesizer.core.midi.MidiListenerProxy;
import java.util.List;

public class AccompanyingMidiListener extends MidiListenerProxy {


    static private Note melodyOff_ = new Note(0, true);
    private TensorFlowAccompanist accompanist_;

    private Note melodyNote_ = melodyOff_;
    private int velocity_ = 100;
    private boolean melodyStruck = false;

    //runs without a timer by reposting this handler at the end of the runnable
    Handler timerHandler = new Handler();
    Runnable timerRunnable = new Runnable() {

        private static final String TAG = "MidiListenerRunnable";

        // beats per minute
        private int bpm_ = 100;

        // 0 : currently no voices active
        // 1-4: current tick
        private int currentTickOfQuarter_ = 0;

        private long lastQuarterBeginTime_ = 0;

        private long nextTickBeginTime_ = 0;

        private List<Note> notesInCurrentTick;

        @Override
        public void run() {
            long millis = System.currentTimeMillis();
            Log.i(TAG, "now: " + millis + " next : " + nextTickBeginTime_);

            if (millis >= nextTickBeginTime_) {
                if (currentTickOfQuarter_ == 4 || currentTickOfQuarter_ == 0) {
                    // continue with next quarter, if melody is played
                    if (melodyNote_.getKey() > 0) {
                        lastQuarterBeginTime_ = millis;
                        currentTickOfQuarter_ = 1;
                    } else {
                        currentTickOfQuarter_ = 0;
                    }
                }

                if (currentTickOfQuarter_ > 0) {
                    // play next tick
                    currentTickOfQuarter_ += 1;
                    nextTickBeginTime_ =
                            lastQuarterBeginTime_ + currentTickOfQuarter_ * 60000 / bpm_;
                    long timeToNextTick;
                    timeToNextTick = nextTickBeginTime_ - millis;

                    List<Note> notesInNewTick = accompanist_.getNextVoices(melodyNote_);
                    if (notesInNewTick.size() != notesInCurrentTick.size()) {
                        // different sizes, terminate all old and start all new notes
                        for (Note note : notesInCurrentTick) {
                            listener_.onNoteOff(0, note.getKey(), velocity_);
                        }
                        for (Note note : notesInNewTick) {
                            listener_.onNoteOn(0, note.getKey(), velocity_);
                        }
                    } else {
                        int count = 0;
                        while (notesInNewTick.size() > count) {
                            Note newNote = notesInNewTick.get(count);
                            Note oldNote = notesInCurrentTick.get(count);
                            if (newNote.getStrike()) {
                                listener_.onNoteOff(0, oldNote.getKey(), velocity_);
                                listener_.onNoteOn(0, newNote.getKey(), velocity_);
                            }
                            count++;
                        }
                    }
                    notesInCurrentTick = notesInNewTick;
                    // the melody note is not new anymore
                    melodyNote_ = new Note(melodyNote_.getKey(), false);
                    timerHandler.postDelayed(this, timeToNextTick);
                }
            }
        }
    };

     /**
      * Creates a new MidiListenerProxy that passes all its events on to listener
      * and adds additional voices to it.
      *
      * @param listener: the listener to forward the midi events to
      * @param assets: android assets
      */
    public AccompanyingMidiListener(MidiListener listener, AssetManager assets) {
        super(listener);
        accompanist_ = TensorFlowAccompanist.create(assets,
                "BachAccompanist.pb", 2, "input", "output", "state_update");

        timerHandler.postDelayed(timerRunnable, 0);
    };

    @Override
    public void onNoteOff(int channel, int note, int velocity) {
        melodyNote_ = melodyOff_;
        listener_.onNoteOff(channel, note, velocity);
    }

    @Override
    public void onNoteOn(int channel, int note, int velocity) {
        melodyNote_ = new Note(note, true);
        velocity_ = velocity;
        timerHandler.postAtFrontOfQueue(timerRunnable);
        listener_.onNoteOn(channel, note, velocity);
    }

}
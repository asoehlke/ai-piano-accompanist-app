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


    private static final String TAG = "AccompanyingListener";
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

        private Note lastMelodyNote_ = melodyOff_;

        @Override
        public void run() {
            long millis = System.currentTimeMillis();

            // record the last played note
            if (melodyNote_.getKey() != 0)
                lastMelodyNote_ = melodyNote_;

            if (millis >= nextTickBeginTime_)
            {
                if (currentTickOfQuarter_ == 4 || currentTickOfQuarter_ == -1)
                {
                    // continue with next quarter, if melody is played
                    if (melodyNote_.getKey() > 0)
                    {
                        lastQuarterBeginTime_ = millis;
                        currentTickOfQuarter_ = 0;
                    }
                    else
                    {
                        currentTickOfQuarter_ = -1;
                        lastMelodyNote_ = melodyNote_;
                        // no melody played, stop accompanying
                        if (notesInCurrentTick != null)
                            for (Note note : notesInCurrentTick)
                                listener_.onNoteOff(0, note.getKey(), velocity_);
                    }
                }

                if (currentTickOfQuarter_ >= 0) {

                    if (lastMelodyNote_.getKey() != 0)
                    {
                        List<Note> notesInNewTick = accompanist_.getNextVoices(lastMelodyNote_);
                        if (notesInCurrentTick == null) {
                            // first call, strike all notes
                            for (Note note : notesInNewTick) {
                                listener_.onNoteOn(0, note.getKey(), velocity_);
                            }
                        } else if (notesInNewTick.size() != notesInCurrentTick.size()) {
                            // different sizes, terminate all old and start all new notes
                            for (Note note : notesInCurrentTick) {
                                listener_.onNoteOff(0, note.getKey(), velocity_);
                            }
                            for (Note note : notesInNewTick) {
                                listener_.onNoteOn(0, note.getKey(), velocity_);
                            }
                        } else {
                            // only change newly struck notes
                            int count = 0;
                            while (notesInNewTick.size() > count) {
                                Note newNote = notesInNewTick.get(count);
                                Note oldNote = notesInCurrentTick.get(count);
                                if (newNote.getStrike()) {
                                    listener_.onNoteOff(0, oldNote.getKey(), velocity_);
                                    listener_.onNoteOn(0, newNote.getKey(), velocity_);
                                }
                                else if (newNote.getKey() != oldNote.getKey())
                                {
                                    // also turn off the old note,
                                    // otherwise we cannot turn it off later
                                    listener_.onNoteOff(0, oldNote.getKey(), velocity_);
                                    listener_.onNoteOn(0, newNote.getKey(), velocity_);
                                }
                                count++;
                            }
                        }
                        notesInCurrentTick = notesInNewTick;
                        // the melody note is not new anymore
                        lastMelodyNote_ = new Note(lastMelodyNote_.getKey(), false);
                        melodyNote_ = new Note(melodyNote_.getKey(), false);
                    }

                    millis = System.currentTimeMillis();
                    currentTickOfQuarter_ += 1;
                    // play next tick
                    nextTickBeginTime_ =
                            lastQuarterBeginTime_ + currentTickOfQuarter_ * 60000 / bpm_;
                    long timeToNextTick;
                    timeToNextTick = nextTickBeginTime_ - millis;
                    Log.i(TAG, "now: " + currentTickOfQuarter_ + "=" + millis
                            + " next : " + nextTickBeginTime_);
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
        Log.i(TAG, "Melody off: " + melodyNote_.getKey());
        melodyNote_ = melodyOff_;
        listener_.onNoteOff(channel, note, velocity);
    }

    @Override
    public void onNoteOn(int channel, int note, int velocity) {
        melodyNote_ = new Note(note, true);
        Log.i(TAG, "Melody on: " + melodyNote_.getKey());
        velocity_ = velocity;
        timerHandler.postAtFrontOfQueue(timerRunnable);
        listener_.onNoteOn(channel, note, velocity);
    }

}
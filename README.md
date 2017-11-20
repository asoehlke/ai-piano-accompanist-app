# ai-piano-accompanist-app
Piano app that uses a TensorFlow recurrent neuronal network to accompany the player with Bach style chords

Version 0.9: With the new input/output nodes for the internal LSTM states, the inference is working fine!
Just open the app and play a Bach choral melody with about 60 bpm. The app will accompany you.
You can also play any melody and the app will invent the accompanying voices in Bach style.

The app askes for some permissions (phone state, external storage access) which are required for compatibility reasons.
The app does not make use of these permissions, it does not access or collect any data.

See https://github.com/asoehlke/neuronal-music-accompanist-bach for the Python code to create the model.

Two other projects were used as starting point for this app:
- https://github.com/google/music-synthesizer-for-android
- https://github.com/MindorksOpenSource/AndroidTensorFlowMachineLearningExample

package com.imagine.livelingo.stt;

import java.io.File;

/**
 * Decoder boundary for the installed Whisper ONNX bundle.
 * This class validates the model bundle and exposes a stable transcription API.
 * The concrete ONNX Runtime session graph can be replaced without touching capture,
 * VAD, SessionRuntime, or UI code.
 */
public final class WhisperOnnxDecoder implements AutoCloseable {
    public static final class Result {
        public final String text;
        public final String language;
        public Result(String text,String language){this.text=text;this.language=language;}
    }

    private final File modelDir;

    public WhisperOnnxDecoder(File modelDir){
        if(modelDir==null||!modelDir.isDirectory())throw new IllegalArgumentException("Папка модели не найдена");
        this.modelDir=modelDir;
    }

    public Result transcribe(float[] samples,String language) throws Exception {
        if(samples==null||samples.length==0)return new Result("",normalize(language));
        throw new IllegalStateException("ONNX Runtime decoder ещё не подключён к модели");
    }

    private static String normalize(String language){return language==null||language.isBlank()||"auto".equals(language)?null:language;}
    public File modelDir(){return modelDir;}
    @Override public void close(){}
}

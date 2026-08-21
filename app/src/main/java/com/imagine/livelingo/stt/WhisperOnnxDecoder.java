package com.imagine.livelingo.stt;

import java.io.File;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import ai.onnxruntime.NodeInfo;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;
import ai.onnxruntime.extensions.OrtxPackage;

/** Local Whisper ONNX decoder compatible with the 6-file bundle used by RTranslator. */
public final class WhisperOnnxDecoder implements AutoCloseable {
    public static final class Result {
        public final String text;
        public final String language;
        public Result(String text,String language){this.text=text;this.language=language;}
    }

    private static final int START_TOKEN_ID=50258;
    private static final int TRANSCRIBE_TOKEN_ID=50359;
    private static final int NO_TIMESTAMPS_TOKEN_ID=50363;
    private static final int EOS=50257;
    private static final int MAX_TOKENS=445;
    private static final int MAX_TOKENS_PER_SECOND=30;
    private static final int DECODER_LAYERS=12;

    private static final String[] LANGUAGES={"en","zh","de","es","ru","ko","fr","ja","pt","tr","pl","ca","nl","ar","sv","it","id","hi","fi","vi","he","uk","el","ms","cs","ro","da","hu","ta","no","th","ur","hr","bg","lt","la","mi","ml","cy","sk","te","fa","lv","bn","sr","az","sl","kn","et","mk","br","eu","is","hy","ne","mn","bs","kk","sq","sw","gl","mr","pa","si","km","sn","yo","so","af","oc","ka","be","tg","sd","gu","am","yi","lo","uz","fo","ht","ps","tk","nn","mt","sa","lb","my","bo","tl","mg","as","tt","haw","ln","ha","ba","jw","su","yue"};
    private static final String[] AUTO_CANDIDATES={"en","ru","de","fr","es","it","pt","pl","tr","uk"};

    private final File modelDir;
    private final OrtEnvironment env;
    private final OrtSession initSession,encoderSession,cacheInitSession,decoderSession,detokenizerSession;
    private final long[] emptyDecoderCacheShape;

    public WhisperOnnxDecoder(File modelDir) throws OrtException {
        if(modelDir==null||!modelDir.isDirectory())throw new IllegalArgumentException("Папка модели не найдена");
        this.modelDir=modelDir;
        env=OrtEnvironment.getEnvironment();
        initSession=createSession("Whisper_initializer.onnx",true,false);
        encoderSession=createSession("Whisper_encoder.onnx",true,true);
        cacheInitSession=createSession("Whisper_cache_initializer.onnx",true,false);
        decoderSession=createSession("Whisper_decoder.onnx",true,false);
        detokenizerSession=createSession("Whisper_detokenizer.onnx",true,false);
        emptyDecoderCacheShape=resolveEmptyDecoderCacheShape();
    }

    private OrtSession createSession(String name,boolean customOps,boolean batchOne) throws OrtException {
        File f=new File(modelDir,name);
        if(!f.isFile())throw new IllegalStateException("Не найден файл модели: "+name);
        OrtSession.SessionOptions o=new OrtSession.SessionOptions();
        if(customOps)o.registerCustomOpLibrary(OrtxPackage.getLibraryPath());
        o.setCPUArenaAllocator(false);
        o.setMemoryPatternOptimization(false);
        o.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT);
        if(batchOne)o.setSymbolicDimensionValue("batch_size",1);
        return env.createSession(f.getAbsolutePath(),o);
    }

    private long[] resolveEmptyDecoderCacheShape(){
        try{
            NodeInfo node=decoderSession.getInputInfo().get("past_key_values.0.decoder.key");
            if(node!=null && node.getInfo() instanceof TensorInfo){
                long[] modelShape=((TensorInfo)node.getInfo()).getShape();
                if(modelShape!=null && modelShape.length==4){
                    long batch=modelShape[0]>0?modelShape[0]:1;
                    long heads=modelShape[1]>0?modelShape[1]:12;
                    long headDim=modelShape[3]>0?modelShape[3]:64;
                    return new long[]{batch,heads,0,headDim};
                }
            }
        }catch(Exception ignored){}
        return new long[]{1,12,0,64};
    }

    public synchronized Result transcribe(float[] samples,String language) throws Exception {
        if(samples==null||samples.length==0)return new Result("",normalize(language));
        String requested=normalize(language);
        int seconds=Math.max(1,(int)Math.ceil(samples.length/16000.0));
        int maxTokens=Math.min(MAX_TOKENS,Math.max(8,seconds*MAX_TOKENS_PER_SECOND));

        try(OnnxTensor audio=OnnxTensor.createTensor(env,FloatBuffer.wrap(samples),new long[]{1,samples.length})){
            Map<String,OnnxTensor> initInputs=new LinkedHashMap<>(); initInputs.put("audio_pcm",audio);
            try(OrtSession.Result initOut=initSession.run(initInputs)){
                OnnxTensor features=(OnnxTensor)initOut.get(0);
                Map<String,OnnxTensor> encInputs=new LinkedHashMap<>(); encInputs.put("input_features",features);
                try(OrtSession.Result encOut=encoderSession.run(encInputs)){
                    OnnxTensor encoderHidden=(OnnxTensor)encOut.get(0);
                    Map<String,OnnxTensor> cacheInputs=new HashMap<>(); cacheInputs.put("encoder_hidden_states",encoderHidden);
                    try(OrtSession.Result cacheOut=cacheInitSession.run(cacheInputs)){
                        String lang=requested!=null?requested:detectLanguage(cacheOut);
                        DecodeResult decoded=decodeTokens(cacheOut,lang,maxTokens);
                        if(decoded.tokens.isEmpty())return new Result("",lang);
                        return new Result(detokenize(decoded.tokens),lang);
                    }
                }
            }
        }
    }

    private String detectLanguage(OrtSession.Result cacheOut) throws Exception {
        String best="en";
        double bestScore=Double.NEGATIVE_INFINITY;
        for(String candidate:AUTO_CANDIDATES){
            double score=scoreLanguage(cacheOut,candidate);
            if(score>bestScore){bestScore=score;best=candidate;}
        }
        return best;
    }

    /** Whisper language is chosen from logits immediately after the start token. */
    private double scoreLanguage(OrtSession.Result cacheOut,String lang) throws Exception {
        OnnxTensor inputIds=null;
        OnnxTensor empty=null;
        try{
            inputIds=OnnxTensor.createTensor(env,IntBuffer.wrap(new int[]{START_TOKEN_ID}),new long[]{1,1});
            Map<String,OnnxTensor> decInputs=new HashMap<>(); decInputs.put("input_ids",inputIds);
            empty=createZeros(emptyDecoderCacheShape);
            bindInitialCache(decInputs,cacheOut,empty);
            try(OrtSession.Result out=decoderSession.run(decInputs)){
                float[][][] logits=(float[][][])((OnnxTensor)out.get("logits").orElseThrow()).getValue();
                return logSoftmaxAt(logits[0][0],getLanguageId(lang));
            }
        }finally{
            if(inputIds!=null)inputIds.close();
            if(empty!=null)empty.close();
        }
    }

    private DecodeResult decodeTokens(OrtSession.Result cacheOut,String lang,int maxTokens) throws Exception {
        ArrayList<Integer> tokens=new ArrayList<>();
        OrtSession.Result prev=null;
        OnnxTensor inputIds=null;
        OnnxTensor empty=null;
        try{
            int[] prompt={START_TOKEN_ID,getLanguageId(lang),TRANSCRIBE_TOKEN_ID,NO_TIMESTAMPS_TOKEN_ID};
            for(int step=0;step<maxTokens+prompt.length;step++){
                int tokenIn=step<prompt.length?prompt[step]:(tokens.isEmpty()?NO_TIMESTAMPS_TOKEN_ID:tokens.get(tokens.size()-1));
                if(inputIds!=null){inputIds.close();inputIds=null;}
                inputIds=OnnxTensor.createTensor(env,IntBuffer.wrap(new int[]{tokenIn}),new long[]{1,1});
                Map<String,OnnxTensor> decInputs=new HashMap<>();decInputs.put("input_ids",inputIds);
                if(prev==null){
                    empty=createZeros(emptyDecoderCacheShape);
                    bindInitialCache(decInputs,cacheOut,empty);
                }else bindRollingCache(decInputs,cacheOut,prev);
                OrtSession.Result cur=decoderSession.run(decInputs);
                if(prev!=null)prev.close();prev=cur;
                if(step<prompt.length-1)continue;
                float[][][] logits=(float[][][])((OnnxTensor)cur.get("logits").orElseThrow()).getValue();
                int next=argmax(logits[0][0]);
                if(next==EOS)break;
                tokens.add(next);
            }
            return new DecodeResult(tokens);
        }finally{
            if(prev!=null)prev.close();
            if(inputIds!=null)inputIds.close();
            if(empty!=null)empty.close();
        }
    }

    private void bindInitialCache(Map<String,OnnxTensor> decInputs,OrtSession.Result cacheOut,OnnxTensor empty){
        for(int i=0;i<DECODER_LAYERS;i++){
            decInputs.put("past_key_values."+i+".decoder.key",empty);
            decInputs.put("past_key_values."+i+".decoder.value",empty);
            decInputs.put("past_key_values."+i+".encoder.key",(OnnxTensor)cacheOut.get("present."+i+".encoder.key").orElseThrow());
            decInputs.put("past_key_values."+i+".encoder.value",(OnnxTensor)cacheOut.get("present."+i+".encoder.value").orElseThrow());
        }
    }

    private void bindRollingCache(Map<String,OnnxTensor> decInputs,OrtSession.Result cacheOut,OrtSession.Result prev){
        for(int i=0;i<DECODER_LAYERS;i++){
            decInputs.put("past_key_values."+i+".decoder.key",(OnnxTensor)prev.get("present."+i+".decoder.key").orElseThrow());
            decInputs.put("past_key_values."+i+".decoder.value",(OnnxTensor)prev.get("present."+i+".decoder.value").orElseThrow());
            decInputs.put("past_key_values."+i+".encoder.key",(OnnxTensor)cacheOut.get("present."+i+".encoder.key").orElseThrow());
            decInputs.put("past_key_values."+i+".encoder.value",(OnnxTensor)cacheOut.get("present."+i+".encoder.value").orElseThrow());
        }
    }

    private String detokenize(ArrayList<Integer> tokens) throws Exception {
        int[] seq=tokens.stream().mapToInt(Integer::intValue).toArray();
        try(OnnxTensor seqTensor=OnnxTensor.createTensor(env,IntBuffer.wrap(seq),new long[]{1,1,seq.length})){
            Map<String,OnnxTensor> detokInputs=new LinkedHashMap<>();detokInputs.put("sequences",seqTensor);
            try(OrtSession.Result detokOut=detokenizerSession.run(detokInputs)){
                String text=((String[][])detokOut.get(0).getValue())[0][0];
                return clean(text);
            }
        }
    }

    private static final class DecodeResult { final ArrayList<Integer> tokens; DecodeResult(ArrayList<Integer> tokens){this.tokens=tokens;} }
    private OnnxTensor createZeros(long[] shape) throws OrtException { long n=1;for(long d:shape)n*=Math.max(0,d);if(n>Integer.MAX_VALUE)throw new OrtException("Tensor too large");return OnnxTensor.createTensor(env,FloatBuffer.wrap(new float[(int)n]),shape); }
    private static int argmax(float[] a){int idx=0;for(int i=1;i<a.length;i++)if(a[i]>a[idx])idx=i;return idx;}
    private static double logSoftmaxAt(float[] logits,int index){if(index<0||index>=logits.length)return Double.NEGATIVE_INFINITY;float max=logits[0];for(int i=1;i<logits.length;i++)if(logits[i]>max)max=logits[i];double sum=0;for(float v:logits)sum+=Math.exp(v-max);return (logits[index]-max)-Math.log(sum);}
    private static String clean(String s){if(s==null)return "";String x=s.replaceAll("<\\|[^>]*\\|>\\s*","").trim().replace("...","");if(x.length()>1&&Character.isLowerCase(x.charAt(0)))x=Character.toUpperCase(x.charAt(0))+x.substring(1);return x.trim();}
    private static int getLanguageId(String lang){for(int i=0;i<LANGUAGES.length;i++)if(LANGUAGES[i].equals(lang))return 50259+i;return 50259;}
    private static String normalize(String language){return language==null||language.isBlank()||"auto".equals(language)?null:language.split("[-_]")[0].toLowerCase();}
    public File modelDir(){return modelDir;}
    @Override public void close(){try{detokenizerSession.close();}catch(Exception ignored){}try{decoderSession.close();}catch(Exception ignored){}try{cacheInitSession.close();}catch(Exception ignored){}try{encoderSession.close();}catch(Exception ignored){}try{initSession.close();}catch(Exception ignored){}}
}

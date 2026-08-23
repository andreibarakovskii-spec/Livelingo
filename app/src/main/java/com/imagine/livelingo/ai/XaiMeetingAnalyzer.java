package com.imagine.livelingo.ai;

import android.os.Handler;
import android.os.Looper;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/** AI meeting analyzer. Supports xAI keys and Groq gsk_ keys without exposing the secret. */
public final class XaiMeetingAnalyzer {
    public interface Callback { void onSuccess(String result); void onError(String message); }
    private static final String XAI_ENDPOINT="https://api.x.ai/v1/responses";
    private static final String GROQ_ENDPOINT="https://api.groq.com/openai/v1/chat/completions";
    private static final String XAI_MODEL="grok-4.6";
    private static final String GROQ_MODEL="openai/gpt-oss-120b";
    private static final int MAX_PAYLOAD_CHARS=120_000;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());

    public static boolean isGroqKey(String key){return key!=null&&key.trim().startsWith("gsk_");}
    public static String providerLabel(String key){return isGroqKey(key)?"Groq · "+GROQ_MODEL:"xAI · "+XAI_MODEL;}

    public void analyze(String payload,String apiKey,Callback callback){
        if(callback==null)return;
        String key=apiKey==null?"":apiKey.trim();
        if(key.isBlank()){callback.onError("Добавьте API-ключ во вкладке AI");return;}
        String source=payload==null?"":payload.trim();
        if(source.isBlank()){callback.onError("Нет данных встречи для анализа");return;}
        worker.execute(()->{
            try{postSuccess(callback,isGroqKey(key)?requestGroq(source,key):requestXai(source,key));}
            catch(Throwable e){postError(callback,safe(e.getMessage()));}
        });
    }

    private String prompt(String payload){
        String clipped=payload.length()>MAX_PAYLOAD_CHARS?payload.substring(payload.length()-MAX_PAYLOAD_CHARS):payload;
        return "Ты — аналитик деловых встреч LiveLingo. Анализируй ТОЛЬКО предоставленную стенограмму и локальный отчёт. Не выдумывай факты, цифры, имена, сроки или причины. Если данных не хватает, прямо напиши 'не указано'. Сохраняй различие между спикерами. Ответ дай на русском языке, компактно и пригодно для бизнеса.\n\n"
                +"Сформируй:\n1) ТЕМА — одно предложение.\n2) КРАТКО — 3–6 ключевых выводов.\n3) РЕШЕНИЯ — только реально принятые решения.\n4) ЗАДАЧИ — кто / что / срок, если это сказано.\n5) ЦИФРЫ И ФАКТЫ — важные значения и условия.\n6) РИСКИ И ОТКРЫТЫЕ ВОПРОСЫ.\n7) СЛЕДУЮЩИЕ ШАГИ.\n8) КАРТА РАЗГОВОРА — темы и связи между ними.\n9) ПРЕЗЕНТАЦИЯ — от 3 до 10 слайдов; количество, порядок и визуальный тип должны зависеть от содержания встречи, а не от фиксированного шаблона. Для каждого слайда укажи: ТИП (hero-number/topic-map/decision/action-board/risk-matrix/quote/timeline/comparison/process), ЗАГОЛОВОК и до 5 коротких тезисов. Не создавай пустые слайды. Для ключевых выводов добавляй подтверждение: спикер и фрагмент реплики, если это есть в стенограмме.\n\nДАННЫЕ ВСТРЕЧИ:\n"+clipped;
    }

    private String requestXai(String payload,String key) throws Exception{
        JSONObject body=new JSONObject();body.put("model",XAI_MODEL);body.put("input",prompt(payload));
        JSONArray include=new JSONArray();include.put("no_inline_citations");body.put("include",include);
        HttpURLConnection c=open(XAI_ENDPOINT,key);write(c,body);
        int code=c.getResponseCode();String raw=read(code>=200&&code<300?c.getInputStream():c.getErrorStream());c.disconnect();
        if(code<200||code>=300)throw new IllegalStateException("xAI HTTP "+code+": "+trimError(raw));
        JSONObject root=new JSONObject(raw);String text=extractXaiText(root);
        if(text.isBlank())throw new IllegalStateException("xAI вернул пустой анализ");return text.trim();
    }

    private String requestGroq(String payload,String key) throws Exception{
        JSONObject body=new JSONObject();body.put("model",GROQ_MODEL);body.put("temperature",0.2);
        JSONArray messages=new JSONArray();
        messages.put(new JSONObject().put("role","system").put("content","Ты аналитик встреч LiveLingo. Не выдумывай факты и опирайся только на стенограмму."));
        messages.put(new JSONObject().put("role","user").put("content",prompt(payload)));
        body.put("messages",messages);
        HttpURLConnection c=open(GROQ_ENDPOINT,key);write(c,body);
        int code=c.getResponseCode();String raw=read(code>=200&&code<300?c.getInputStream():c.getErrorStream());c.disconnect();
        if(code<200||code>=300)throw new IllegalStateException("Groq HTTP "+code+": "+trimError(raw));
        JSONObject root=new JSONObject(raw);JSONArray choices=root.optJSONArray("choices");
        String text=choices!=null&&choices.length()>0?choices.optJSONObject(0).optJSONObject("message").optString("content",""):"";
        if(text.isBlank())throw new IllegalStateException("Groq вернул пустой анализ");return text.trim();
    }

    private static HttpURLConnection open(String endpoint,String key)throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL(endpoint).openConnection();
        c.setRequestMethod("POST");c.setConnectTimeout(15_000);c.setReadTimeout(90_000);c.setDoOutput(true);
        c.setRequestProperty("Authorization","Bearer "+key);c.setRequestProperty("Content-Type","application/json");c.setRequestProperty("Accept","application/json");return c;
    }
    private static void write(HttpURLConnection c,JSONObject body)throws Exception{byte[] bytes=body.toString().getBytes(StandardCharsets.UTF_8);try(OutputStream out=c.getOutputStream()){out.write(bytes);}}
    private static String extractXaiText(JSONObject root){String direct=root.optString("output_text","");if(!direct.isBlank())return direct;JSONArray output=root.optJSONArray("output");if(output==null)return "";StringBuilder result=new StringBuilder();for(int i=0;i<output.length();i++){JSONObject item=output.optJSONObject(i);if(item==null||!"message".equals(item.optString("type")))continue;JSONArray content=item.optJSONArray("content");if(content==null)continue;for(int j=0;j<content.length();j++){JSONObject part=content.optJSONObject(j);if(part!=null&&"output_text".equals(part.optString("type"))){String t=part.optString("text","");if(!t.isBlank()){if(result.length()>0)result.append('\n');result.append(t);}}}}return result.toString();}
    private static String read(InputStream in)throws Exception{if(in==null)return "";StringBuilder s=new StringBuilder();try(BufferedReader r=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8))){String line;while((line=r.readLine())!=null)s.append(line);}return s.toString();}
    private static String trimError(String raw){if(raw==null||raw.isBlank())return "ошибка";try{JSONObject j=new JSONObject(raw);JSONObject e=j.optJSONObject("error");if(e!=null)return safe(e.optString("message",raw));}catch(Exception ignored){}return raw.length()>220?raw.substring(0,220)+"…":raw;}
    private void postSuccess(Callback c,String s){main.post(()->c.onSuccess(s));}
    private void postError(Callback c,String s){main.post(()->c.onError(s));}
    private static String safe(String s){return s==null||s.isBlank()?"неизвестная ошибка":s;}
    public void close(){worker.shutdownNow();}
}

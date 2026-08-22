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

/** Minimal xAI Responses API client used only for explicit meeting analysis. */
public final class XaiMeetingAnalyzer {
    public interface Callback { void onSuccess(String result); void onError(String message); }
    private static final String ENDPOINT="https://api.x.ai/v1/responses";
    private static final String MODEL="grok-4.6";
    private static final int MAX_PAYLOAD_CHARS=120_000;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());

    public void analyze(String payload,String apiKey,Callback callback){
        if(callback==null)return;
        String key=apiKey==null?"":apiKey.trim();
        if(key.isBlank()){callback.onError("Добавьте xAI API-ключ во вкладке AI");return;}
        String source=payload==null?"":payload.trim();
        if(source.isBlank()){callback.onError("Нет данных встречи для анализа");return;}
        worker.execute(()->{
            try{postSuccess(callback,request(source,key));}
            catch(Throwable e){postError(callback,safe(e.getMessage()));}
        });
    }

    private String request(String payload,String key) throws Exception{
        String clipped=payload.length()>MAX_PAYLOAD_CHARS?payload.substring(payload.length()-MAX_PAYLOAD_CHARS):payload;
        String prompt="Ты — аналитик деловых встреч LiveLingo. Анализируй ТОЛЬКО предоставленную стенограмму и локальный отчёт. Не выдумывай факты, цифры, имена, сроки или причины. Если данных не хватает, прямо напиши 'не указано'. Сохраняй различие между спикерами. Ответ дай на русском языке, компактно и пригодно для бизнеса.\n\n"
                +"Сформируй:\n1) ТЕМА — одно предложение.\n2) КРАТКО — 3–6 ключевых выводов.\n3) РЕШЕНИЯ — только реально принятые решения.\n4) ЗАДАЧИ — кто / что / срок, если это сказано.\n5) ЦИФРЫ И ФАКТЫ — важные значения и условия.\n6) РИСКИ И ОТКРЫТЫЕ ВОПРОСЫ.\n7) СЛЕДУЮЩИЕ ШАГИ.\n8) ПРЕЗЕНТАЦИЯ — от 3 до 8 слайдов; количество и структура должны зависеть от содержания встречи, а не от фиксированного шаблона. Для каждого слайда: заголовок и до 5 коротких пунктов. Не создавай пустые слайды.\n\nДАННЫЕ ВСТРЕЧИ:\n"+clipped;

        JSONObject body=new JSONObject();
        body.put("model",MODEL);
        body.put("input",prompt);
        JSONArray include=new JSONArray();include.put("no_inline_citations");body.put("include",include);

        HttpURLConnection c=(HttpURLConnection)new URL(ENDPOINT).openConnection();
        c.setRequestMethod("POST");c.setConnectTimeout(15_000);c.setReadTimeout(90_000);c.setDoOutput(true);
        c.setRequestProperty("Authorization","Bearer "+key);c.setRequestProperty("Content-Type","application/json");c.setRequestProperty("Accept","application/json");
        byte[] bytes=body.toString().getBytes(StandardCharsets.UTF_8);
        try(OutputStream out=c.getOutputStream()){out.write(bytes);}
        int code=c.getResponseCode();
        String raw=read(code>=200&&code<300?c.getInputStream():c.getErrorStream());c.disconnect();
        if(code<200||code>=300)throw new IllegalStateException("xAI HTTP "+code+": "+trimError(raw));
        JSONObject root=new JSONObject(raw);String text=extractOutputText(root);
        if(text.isBlank())throw new IllegalStateException("xAI вернул пустой анализ");
        return text.trim();
    }

    private static String extractOutputText(JSONObject root){
        String direct=root.optString("output_text","");if(!direct.isBlank())return direct;
        JSONArray output=root.optJSONArray("output");if(output==null)return "";
        StringBuilder result=new StringBuilder();
        for(int i=0;i<output.length();i++){
            JSONObject item=output.optJSONObject(i);if(item==null||!"message".equals(item.optString("type")))continue;
            JSONArray content=item.optJSONArray("content");if(content==null)continue;
            for(int j=0;j<content.length();j++){
                JSONObject part=content.optJSONObject(j);if(part!=null&&"output_text".equals(part.optString("type"))){String t=part.optString("text","");if(!t.isBlank()){if(result.length()>0)result.append('\n');result.append(t);}}
            }
        }
        return result.toString();
    }
    private static String read(InputStream in) throws Exception{if(in==null)return "";StringBuilder s=new StringBuilder();try(BufferedReader r=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8))){String line;while((line=r.readLine())!=null)s.append(line);}return s.toString();}
    private static String trimError(String raw){if(raw==null||raw.isBlank())return "ошибка";try{JSONObject j=new JSONObject(raw);JSONObject e=j.optJSONObject("error");if(e!=null)return safe(e.optString("message",raw));}catch(Exception ignored){}return raw.length()>220?raw.substring(0,220)+"…":raw;}
    private void postSuccess(Callback c,String s){main.post(()->c.onSuccess(s));}
    private void postError(Callback c,String s){main.post(()->c.onError(s));}
    private static String safe(String s){return s==null||s.isBlank()?"неизвестная ошибка":s;}
    public void close(){worker.shutdownNow();}
}

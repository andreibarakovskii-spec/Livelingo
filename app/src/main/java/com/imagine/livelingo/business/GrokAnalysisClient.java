package com.imagine.livelingo.business;

import android.content.Context;
import com.imagine.livelingo.security.SecureXaiKeyStore;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** xAI Grok client for adaptive meeting analysis and slide generation. */
public final class GrokAnalysisClient {
    public interface Callback { void onResult(String text); void onError(String message); }
    private static final String ENDPOINT="https://api.x.ai/v1/chat/completions";
    private final SecureXaiKeyStore keys;
    private final ExecutorService io=Executors.newSingleThreadExecutor();

    public GrokAnalysisClient(Context context){keys=new SecureXaiKeyStore(context);}
    public boolean isConfigured(){return keys.hasKey();}
    public void saveApiKey(String key) throws Exception {keys.save(key);}
    public void clearApiKey(){keys.clear();}

    public void analyzeMeeting(String payload, Callback cb){
        String system="Ты бизнес-аналитик LiveLingo. Проанализируй стенограмму встречи. Не выдумывай факты. Выдели: краткое резюме, решения, задачи с ответственными и сроками если они названы, риски, открытые вопросы, следующие шаги и важные цитаты/факты. Ответ на русском, компактно и структурированно.";
        request(system,"СТЕНОГРАММА И ДАННЫЕ ВСТРЕЧИ:\n"+limit(payload,50000),cb);
    }

    public void buildPresentation(String payload, Callback cb){
        String system="Ты создаёшь содержательную бизнес-презентацию по реальному диалогу. Не используй фиксированный шаблон из одинаковых 5 слайдов. Сам определи количество и структуру слайдов по смыслу встречи. На каждом слайде: короткий заголовок и 2-5 конкретных тезисов. При необходимости добавь слайды: проблема, данные, варианты, принятое решение, план, риски, KPI, сроки. Не добавляй того, чего нет в разговоре. Верни текст в формате: СЛАЙД 1 · заголовок, затем пункты; далее следующие слайды.";
        request(system,"ПОСТРОЙ ПРЕЗЕНТАЦИЮ ПО ЭТОЙ ВСТРЕЧЕ:\n"+limit(payload,50000),cb);
    }

    public void answerAboutMeeting(String payload,String question,Callback cb){
        String system="Отвечай только по содержанию предоставленной встречи. Если ответа в стенограмме нет, прямо скажи, что это не обсуждалось. Сначала дай короткий ответ, затем подтверждающие важные моменты.";
        request(system,"ВОПРОС: "+question+"\n\nВСТРЕЧА:\n"+limit(payload,50000),cb);
    }

    private void request(String system,String user,Callback cb){
        final String apiKey=keys.load();
        if(apiKey==null||apiKey.isBlank()){cb.onError("Добавьте xAI API key в Профиле");return;}
        io.execute(()->{
            HttpURLConnection c=null;
            try{
                c=(HttpURLConnection)new URL(ENDPOINT).openConnection();
                c.setConnectTimeout(15000);c.setReadTimeout(90000);c.setRequestMethod("POST");c.setDoOutput(true);
                c.setRequestProperty("Authorization","Bearer "+apiKey);c.setRequestProperty("Content-Type","application/json");
                JSONObject body=new JSONObject();body.put("model","grok-4.6");body.put("temperature",0.2);
                JSONArray messages=new JSONArray();
                messages.put(new JSONObject().put("role","system").put("content",system));
                messages.put(new JSONObject().put("role","user").put("content",user));
                body.put("messages",messages);
                byte[] bytes=body.toString().getBytes(StandardCharsets.UTF_8);
                try(OutputStream os=c.getOutputStream()){os.write(bytes);}
                int code=c.getResponseCode();InputStream in=code>=200&&code<300?c.getInputStream():c.getErrorStream();
                String raw=readAll(in);
                if(code<200||code>=300){cb.onError("Grok API: HTTP "+code+" · "+shorten(raw,300));return;}
                JSONObject root=new JSONObject(raw);JSONArray choices=root.optJSONArray("choices");
                String text=choices!=null&&choices.length()>0?choices.getJSONObject(0).getJSONObject("message").optString("content",""):"";
                if(text.isBlank())cb.onError("Grok вернул пустой ответ"); else cb.onResult(text.trim());
            }catch(Exception e){cb.onError("Grok: "+(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage()));}
            finally{if(c!=null)c.disconnect();}
        });
    }

    private static String readAll(InputStream in)throws Exception{if(in==null)return "";StringBuilder s=new StringBuilder();try(BufferedReader r=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8))){String line;while((line=r.readLine())!=null)s.append(line);}return s.toString();}
    private static String limit(String s,int max){if(s==null)return "";return s.length()<=max?s:s.substring(0,max)+"\n[часть стенограммы обрезана по лимиту]";}
    private static String shorten(String s,int max){if(s==null)return "";return s.length()<=max?s:s.substring(0,max)+"…";}
}

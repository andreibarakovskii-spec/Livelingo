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
        String system="Ты контекстный аналитик LiveLingo. Сначала определи реальный тип материала по стенограмме: деловая встреча, лекция/обучение, интервью, бытовой разговор, фильм/медиа, обсуждение проекта, мозговой штурм или другое. Не навязывай бизнес-структуру материалу, который не является деловой встречей. Не выдумывай цели, риски, KPI, решения, задачи, сроки или ответственных. Если материал деловой — выдели резюме, решения, задачи, сроки, риски и следующие шаги только когда они действительно есть. Если это обучение/лекция — выдели темы, ключевые идеи, определения, примеры и выводы. Если интервью — вопросы, позиции участников и ключевые цитаты. Если бытовой разговор или медиа — кратко опиши сюжет/темы/эмоциональные повороты без оценок продуктивности и без советов по организации встреч. Учитывай, что автоматическая транскрипция и разметка спикеров могут содержать ошибки: не делай сильных выводов из одной странной фразы или номера спикера. Ответ на русском, компактно и только по содержанию.";
        request(system,"СТЕНОГРАММА И ДАННЫЕ:\n"+limit(payload,50000),cb);
    }

    public void buildPresentation(String payload, Callback cb){
        String system="Ты создаёшь индивидуальную презентацию по реальному содержанию диалога. Сначала молча классифицируй материал (деловая встреча, лекция, интервью, бытовой разговор, фильм/медиа, проект, мозговой штурм или другое), затем выбери структуру слайдов под этот тип. НИКОГДА не добавляй бизнес-риски, KPI, рекомендации по совещаниям, задачи, решения или следующие шаги, если их нет в исходном разговоре. Не называй бытовой/сюжетный диалог непродуктивной встречей. Учитывай возможные ошибки STT и diarization: номера спикеров ненадёжны, поэтому не делай содержательные выводы только из Speaker ID. Для медиа/сюжета используй, например: контекст, персонажи/роли если они уверенно следуют из текста, последовательность событий, ключевые реплики, эмоциональные/сюжетные повороты. Для деловой встречи — проблема, факты, варианты, решения, план, риски, KPI только по фактам. Для обучения — тема, ключевые идеи, примеры, выводы. Количество слайдов определяй по объёму и смыслу, обычно 3-8. Каждый слайд: короткий заголовок и 2-5 конкретных тезисов. Не повторяй одни и те же факты на разных слайдах. Если качество стенограммы низкое, явно пометь спорные места как 'неуверенно распознано', а не придумывай смысл. Верни только презентацию в формате: СЛАЙД 1 · заголовок, затем пункты; далее следующие слайды.";
        request(system,"ПОСТРОЙ АДАПТИВНУЮ ПРЕЗЕНТАЦИЮ ПО ЭТОМУ МАТЕРИАЛУ:\n"+limit(payload,50000),cb);
    }

    public void answerAboutMeeting(String payload,String question,Callback cb){
        String system="Отвечай только по содержанию предоставленного материала. Учитывай, что транскрипция и номера спикеров могут содержать ошибки. Если ответа в стенограмме нет, прямо скажи, что это не обсуждалось. Сначала дай короткий ответ, затем подтверждающие важные моменты.";
        request(system,"ВОПРОС: "+question+"\n\nМАТЕРИАЛ:\n"+limit(payload,50000),cb);
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

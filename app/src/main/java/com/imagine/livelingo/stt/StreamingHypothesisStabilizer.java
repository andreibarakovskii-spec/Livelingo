package com.imagine.livelingo.stt;

/**
 * Prevents partial Whisper hypotheses from flickering on every decode.
 * A partial is emitted only after a stable word prefix survives consecutive updates.
 */
public final class StreamingHypothesisStabilizer {
    private String previous="";
    private String lastEmitted="";

    public synchronized String accept(String text, boolean isFinal) {
        String current=normalize(text);
        if(current.isEmpty()) return "";
        if(isFinal){
            previous="";
            lastEmitted=current;
            return current;
        }

        String stablePrefix=commonPrefixByWords(previous,current);
        boolean previousWasExactPrefix=!previous.isEmpty() && startsWithWholeWords(current,previous);
        previous=current;

        if(stablePrefix.isEmpty()) return "";

        // When the entire previous hypothesis is merely extended, keep its last word
        // provisional for one more decode. Whisper often revises exactly that tail word.
        if(previousWasExactPrefix && wordCount(stablePrefix)>1){
            stablePrefix=dropLastWord(stablePrefix);
        }

        if(stablePrefix.isEmpty() || stablePrefix.equals(lastEmitted)) return "";
        if(wordCount(stablePrefix)<2 && wordCount(current)>1) return "";

        // Never move the UI backwards when a later hypothesis revises an older prefix.
        if(!lastEmitted.isEmpty() && wordCount(stablePrefix)<=wordCount(lastEmitted)){
            if(startsWithWholeWords(lastEmitted,stablePrefix)) return "";
        }

        lastEmitted=stablePrefix;
        return stablePrefix;
    }

    public synchronized void reset(){previous="";lastEmitted="";}

    private static String commonPrefixByWords(String a,String b){
        if(a.isEmpty()||b.isEmpty())return "";
        String[] x=a.split("\\s+"),y=b.split("\\s+");
        int n=Math.min(x.length,y.length),i=0;
        while(i<n && x[i].equalsIgnoreCase(y[i]))i++;
        if(i==0)return "";
        StringBuilder sb=new StringBuilder();
        for(int j=0;j<i;j++){if(j>0)sb.append(' ');sb.append(y[j]);}
        return sb.toString().trim();
    }

    private static boolean startsWithWholeWords(String text,String prefix){
        if(prefix.isEmpty())return true;
        if(text.equalsIgnoreCase(prefix))return true;
        if(text.length()<=prefix.length())return false;
        return text.regionMatches(true,0,prefix,0,prefix.length()) && Character.isWhitespace(text.charAt(prefix.length()));
    }

    private static String dropLastWord(String s){
        int i=s.lastIndexOf(' ');
        return i<=0?"":s.substring(0,i).trim();
    }

    private static int wordCount(String s){return s.isBlank()?0:s.trim().split("\\s+").length;}
    private static String normalize(String s){return s==null?"":s.replaceAll("\\s+"," ").trim();}
}

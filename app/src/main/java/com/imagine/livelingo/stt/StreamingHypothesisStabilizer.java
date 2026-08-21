package com.imagine.livelingo.stt;

/**
 * Prevents partial Whisper hypotheses from flickering on every decode.
 * A partial is emitted only after a small stable prefix has survived consecutive updates.
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
        previous=current;
        if(stablePrefix.isEmpty() || stablePrefix.equals(lastEmitted)) return "";
        if(wordCount(stablePrefix)<2 && wordCount(current)>1) return "";
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

    private static int wordCount(String s){return s.isBlank()?0:s.trim().split("\\s+").length;}
    private static String normalize(String s){return s==null?"":s.replaceAll("\\s+"," ").trim();}
}

package com.imagine.livelingo.security;

import android.content.Context;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

/** Read-only repository facade for encrypted meeting history. */
public final class MeetingVaultRepository {
    public static final class Item {
        public final String id;
        public final long createdAt;
        public final String title;
        public final String preview;
        public Item(String id,long createdAt,String title,String preview){this.id=id;this.createdAt=createdAt;this.title=title;this.preview=preview;}
        public String displayDate(){return DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(new Date(createdAt));}
    }

    private final EncryptedMeetingVault vault;
    public MeetingVaultRepository(Context context){vault=new EncryptedMeetingVault(context);}

    public List<Item> list() throws Exception {
        List<Item> out=new ArrayList<>();
        for(String id:vault.ids()){
            EncryptedMeetingVault.StoredMeeting m=vault.load(id);
            if(m==null) continue;
            String p=m.payload==null?"":m.payload.replace('\n',' ').trim();
            if(p.length()>120)p=p.substring(0,120)+"…";
            out.add(new Item(m.id,m.createdAt,m.title,p));
        }
        out.sort(Comparator.comparingLong((Item x)->x.createdAt).reversed());
        return Collections.unmodifiableList(out);
    }

    public EncryptedMeetingVault.StoredMeeting load(String id) throws Exception {return vault.load(id);}
    public void delete(String id){vault.delete(id);}
    public void wipeAll(){vault.wipeAll();}
}

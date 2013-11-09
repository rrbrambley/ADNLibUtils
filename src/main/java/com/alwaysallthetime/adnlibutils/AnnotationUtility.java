package com.alwaysallthetime.adnlibutils;

import android.util.Log;

import com.alwaysallthetime.adnlib.Annotations;
import com.alwaysallthetime.adnlib.data.Annotation;
import com.alwaysallthetime.adnlib.data.Message;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Created by brambley on 10/9/13.
 */
public class AnnotationUtility {
    private static final String TAG = "ADNLibUtils_AnnotationUtility";

    public static final String OEMBED_TYPE_PHOTO = "photo";
    public static final String OEMBED_TYPE_HTML5VIDEO = "html5video";

    private static SimpleDateFormat mIso8601Format;

    public static Date getJournalEntryDate(Message message) {
        Annotation displayDate = message.getFirstAnnotationOfType(Annotations.OHAI_DISPLAY_DATE);
        if(displayDate != null) {
            String date = (String) displayDate.getValue().get("date");
            if(date != null) {
                return getDateFromIso8601String(date);
            }
        }
        return message.getCreatedAt();
    }

    public static Date getDateFromIso8601String(String date) {
        initFormatter();
        try {
            return mIso8601Format.parse(date);
        } catch(ParseException e) {
            Log.d(TAG, e.getMessage(), e);
        }
        return null;
    }

    public static String getIso8601StringfromDate(Date date) {
        initFormatter();
        return mIso8601Format.format(date);
    }

    public static Annotation newDisplayDateAnnotation(Date date) {
        Annotation displayDateAnnotation = new Annotation(Annotations.OHAI_DISPLAY_DATE);
        HashMap<String, Object> value = new HashMap<String, Object>(1);
        value.put("date", getIso8601StringfromDate(date));
        displayDateAnnotation.setValue(value);
        return displayDateAnnotation;
    }

    public static Annotation getFirstOEmbedPhotoAnnotation(Message message) {
        Annotation oEmbedAnnotation = message.getFirstAnnotationOfType(Annotations.OEMBED);
        if(oEmbedAnnotation != null && OEMBED_TYPE_PHOTO.equals(oEmbedAnnotation.getValue().get("type"))) {
            return oEmbedAnnotation;
        }
        return null;
    }

    public static String getFirstOEmbedPhotoUrl(Message message) {
        Annotation oEmbedAnnotation = getFirstOEmbedPhotoAnnotation(message);
        if(oEmbedAnnotation != null) {
            return (String) oEmbedAnnotation.getValue().get("url");
        }
        return null;
    }

    public static String getFirstHTML5VideoSource(Message message) {
        Annotation oEmbedAnnotation = message.getFirstAnnotationOfType(Annotations.OEMBED);
        if(OEMBED_TYPE_HTML5VIDEO.equals(oEmbedAnnotation.getValue().get("type"))) {
            List<Map<String, String>> sources = (List<Map<String, String>>) oEmbedAnnotation.getValue().get("sources");
            if(sources != null && sources.size() > 0) {
                Map<String, String> firstSource = sources.get(0);
                String url = firstSource.get("url");
                if(url != null) {
                    return url;
                }
            }
        }
        return null;
    }

    private static void initFormatter() {
        if(mIso8601Format == null) {
            mIso8601Format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
        }
    }
}

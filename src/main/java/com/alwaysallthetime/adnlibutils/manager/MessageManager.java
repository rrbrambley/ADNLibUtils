package com.alwaysallthetime.adnlibutils.manager;

import android.content.Context;
import android.location.Address;
import android.location.Geocoder;
import android.util.Log;

import com.alwaysallthetime.adnlib.Annotations;
import com.alwaysallthetime.adnlib.AppDotNetClient;
import com.alwaysallthetime.adnlib.QueryParameters;
import com.alwaysallthetime.adnlib.data.Annotation;
import com.alwaysallthetime.adnlib.data.Message;
import com.alwaysallthetime.adnlib.data.MessageList;
import com.alwaysallthetime.adnlib.response.MessageListResponseHandler;
import com.alwaysallthetime.adnlib.response.MessageResponseHandler;
import com.alwaysallthetime.adnlibutils.MessagePlus;
import com.alwaysallthetime.adnlibutils.db.ADNDatabase;
import com.alwaysallthetime.adnlibutils.db.OrderedMessageBatch;
import com.alwaysallthetime.adnlibutils.model.DisplayLocation;
import com.alwaysallthetime.adnlibutils.model.Geolocation;
import com.alwaysallthetime.asyncgeocoder.AsyncGeocoder;
import com.alwaysallthetime.asyncgeocoder.response.AsyncGeocoderResponseHandler;
import com.alwaysallthetime.asyncgeocoder.util.AddressUtility;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class MessageManager {

    private static final String TAG = "ADNLibUtils_MessageManager";

    /*
     * public data structures
     */
    public interface MessageManagerResponseHandler {
        public void onSuccess(final List<MessagePlus> responseData, final boolean appended);
        public void onError(Exception exception);
    }

    public interface MessageRefreshResponseHandler {
        public void onSuccess(final MessagePlus responseData);
        public void onError(Exception exception);
    }

    public interface MessageDeletionResponseHandler {
        public void onSuccess();
        public void onError(Exception exception);
    }

    /**
     * A MessageDisplayDateAdapter can be used to return a date for which a Message should be
     * associated. This is most typically used when Message.getCreatedAt() should not be used
     * for sort order.
     */
    public interface MessageDisplayDateAdapter {
        public Date getDisplayDate(Message message);
    }

    private Context mContext;
    private AppDotNetClient mClient;
    private MessageManagerConfiguration mConfiguration;

    private HashMap<String, LinkedHashMap<String, MessagePlus>> mMessages;
    private HashMap<String, QueryParameters> mParameters;
    private HashMap<String, MinMaxPair> mMinMaxPairs;

    public MessageManager(Context context, AppDotNetClient client, MessageManagerConfiguration configuration) {
        mContext = context;
        mClient = client;
        mConfiguration = configuration;

        mMessages = new HashMap<String, LinkedHashMap<String, MessagePlus>>();
        mMinMaxPairs = new HashMap<String, MinMaxPair>();
        mParameters = new HashMap<String, QueryParameters>();
    }

    /**
     * Load persisted messages that were previously stored in the sqlite database.
     *
     * @param channelId the id of the channel for which messages should be loaded.
     * @param limit the maximum number of messages to load from the database.
     * @return a LinkedHashMap containing the newly loaded messages, mapped from message id
     * to Message Object. If no messages were loaded, then an empty Map is returned.
     *
     * @see com.alwaysallthetime.adnlibutils.manager.MessageManager.MessageManagerConfiguration#setDatabaseInsertionEnabled(boolean)
     */
    public synchronized LinkedHashMap<String, MessagePlus> loadPersistedMessages(String channelId, int limit) {
        ADNDatabase database = ADNDatabase.getInstance(mContext);

        Date beforeDate = null;
        MinMaxPair minMaxPair = getMinMaxPair(channelId);
        if(minMaxPair.minId != null) {
            MessagePlus message = mMessages.get(channelId).get(minMaxPair.minId);
            beforeDate = message.getDisplayDate();
        }
        OrderedMessageBatch orderedMessageBatch = database.getMessages(channelId, beforeDate, limit);
        LinkedHashMap<String, MessagePlus> messages = orderedMessageBatch.getMessages();
        MinMaxPair dbMinMaxPair = orderedMessageBatch.getMinMaxPair();
        minMaxPair = minMaxPair.combine(dbMinMaxPair);

        LinkedHashMap<String, MessagePlus> channelMessages = mMessages.get(channelId);
        if(channelMessages != null) {
            channelMessages.putAll(messages);
        } else {
            mMessages.put(channelId, messages);
        }

        mMinMaxPairs.put(channelId, minMaxPair);

        if(mConfiguration.isLocationLookupEnabled) {
            lookupLocation(messages.values());
        }

        //this should always return only the newly loaded messages.
        return messages;
    }

    private void lookupLocation(Collection<MessagePlus> messages) {
        final ADNDatabase database = ADNDatabase.getInstance(mContext);

        for(MessagePlus messagePlus : messages) {
            Message message = messagePlus.getMessage();

            Annotation checkin = message.getFirstAnnotationOfType(Annotations.CHECKIN);
            if(checkin != null) {
                messagePlus.setDisplayLocation(DisplayLocation.fromCheckinAnnotation(checkin));
                if(mConfiguration.isDatabaseInsertionEnabled) {
                    database.insertOrReplaceLocationInstance(messagePlus);
                }
                continue;
            }

            Annotation ohaiLocation = message.getFirstAnnotationOfType(Annotations.OHAI_LOCATION);
            if(ohaiLocation != null) {
                messagePlus.setDisplayLocation(DisplayLocation.fromOhaiLocation(ohaiLocation));
                if(mConfiguration.isDatabaseInsertionEnabled) {
                    database.insertOrReplaceLocationInstance(messagePlus);
                }
                continue;
            }

            Annotation geoAnnotation = message.getFirstAnnotationOfType(Annotations.GEOLOCATION);
            if(geoAnnotation != null) {
                HashMap<String,Object> value = geoAnnotation.getValue();
                final double latitude = (Double)value.get("latitude");
                final double longitude = (Double)value.get("longitude");
                Geolocation geolocationObj = database.getGeolocation(latitude, longitude);
                if(geolocationObj != null) {
                    messagePlus.setDisplayLocation(DisplayLocation.fromGeolocation(geolocationObj));
                    if(mConfiguration.isDatabaseInsertionEnabled) {
                        database.insertOrReplaceLocationInstance(messagePlus);
                    }
                    continue;
                } else {
                    reverseGeocode(messagePlus, latitude, longitude);
                }
            }
        }
    }

    private void reverseGeocode(final MessagePlus messagePlus, final double latitude, final double longitude) {
        if(Geocoder.isPresent()) {
            AsyncGeocoder.getInstance(mContext).getFromLocation(latitude, longitude, 5, new AsyncGeocoderResponseHandler() {
                @Override
                public void onSuccess(final List<Address> addresses) {
                    final String loc = AddressUtility.getAddressString(addresses);
                    if(loc != null) {
                        ADNDatabase database = ADNDatabase.getInstance(mContext);
                        Geolocation geolocation = new Geolocation(loc, latitude, longitude);
                        messagePlus.setDisplayLocation(DisplayLocation.fromGeolocation(geolocation));

                        if(mConfiguration.isDatabaseInsertionEnabled) {
                            database.insertOrReplaceGeolocation(geolocation);
                            database.insertOrReplaceLocationInstance(messagePlus);
                        }
                    }
                    if(mConfiguration.locationLookupHandler != null) {
                        mConfiguration.locationLookupHandler.onSuccess(messagePlus);
                    }
                }

                @Override
                public void onException(Exception exception) {
                    Log.d(TAG, exception.getMessage(), exception);
                    if(mConfiguration.locationLookupHandler != null) {
                        mConfiguration.locationLookupHandler.onException(messagePlus, exception);
                    }
                }
            });
        }
    }

    public Map<String, MessagePlus> getMessageMap(String channelId) {
        return mMessages.get(channelId);
    }

    public List<MessagePlus> getMessageList(String channelId) {
        Map<String, MessagePlus> messageMap = mMessages.get(channelId);
        if(messageMap == null) {
            return null;
        }
        MessagePlus[] messages = messageMap.values().toArray(new MessagePlus[0]);
        return Arrays.asList(messages);
    }

    public void setParameters(String channelId, QueryParameters parameters) {
        mParameters.put(channelId, parameters);
    }

    private synchronized MinMaxPair getMinMaxPair(String channelId) {
        MinMaxPair minMaxPair = mMinMaxPairs.get(channelId);
        if(minMaxPair == null) {
            minMaxPair = new MinMaxPair();
            mMinMaxPairs.put(channelId, minMaxPair);
        }
        return minMaxPair;
    }

    public synchronized void clearMessages(String channelId) {
        mMinMaxPairs.put(channelId, null);
        LinkedHashMap<String, MessagePlus> channelMessages = mMessages.get(channelId);
        if(channelMessages != null) {
            channelMessages.clear();
            ADNDatabase.getInstance(mContext).deleteMessages(channelId);
        }
    }

    public synchronized void retrieveMessages(String channelId, MessageManagerResponseHandler listener) {
        MinMaxPair minMaxPair = getMinMaxPair(channelId);
        retrieveMessages(channelId, minMaxPair.maxId, minMaxPair.minId, listener);
    }

    public synchronized void retrieveNewestMessages(String channelId, MessageManagerResponseHandler listener) {
        retrieveMessages(channelId, getMinMaxPair(channelId).maxId, null, listener);
    }

    public synchronized void retrieveMoreMessages(String channelId, MessageManagerResponseHandler listener) {
        retrieveMessages(channelId, null, getMinMaxPair(channelId).minId, listener);
    }

    public synchronized void createMessage(final String channelId, final Message message, final MessageManagerResponseHandler handler) {
        mClient.createMessage(channelId, message, new MessageResponseHandler() {
            @Override
            public void onSuccess(Message responseData) {
                //we finish this off by retrieving the newest messages in case we were missing any
                //that came before the one we just created.
                retrieveNewestMessages(channelId, handler);
            }

            @Override
            public void onError(Exception error) {
                super.onError(error);
                handler.onError(error);
            }
        });
    }

    public synchronized void deleteMessage(final Message message, final MessageDeletionResponseHandler handler) {
        mClient.deleteMessage(message, new MessageResponseHandler() {
            @Override
            public void onSuccess(Message responseData) {
                LinkedHashMap<String, MessagePlus> channelMessages = mMessages.get(responseData.getChannelId());
                channelMessages.remove(responseData.getId());

                ADNDatabase database = ADNDatabase.getInstance(mContext);
                database.deleteMessage(message); //this one because the deleted one doesn't have the entities.

                handler.onSuccess();
            }

            @Override
            public void onError(Exception error) {
                super.onError(error);
                handler.onError(error);
            }
        });
    }

    public synchronized void refreshMessage(final Message message, final MessageRefreshResponseHandler handler) {
        final String channelId = message.getChannelId();
        mClient.retrieveMessage(channelId, message.getId(), mParameters.get(channelId), new MessageResponseHandler() {
            @Override
            public void onSuccess(Message responseData) {
                MessagePlus mPlus = new MessagePlus(responseData);
                mPlus.setDisplayDate(getAdjustedDate(responseData));

                LinkedHashMap<String, MessagePlus> channelMessages = mMessages.get(channelId);
                if(channelMessages != null) { //could be null of channel messages weren't loaded first, etc.
                    channelMessages.put(responseData.getId(), mPlus);
                }

                if(mConfiguration.isDatabaseInsertionEnabled) {
                    ADNDatabase database = ADNDatabase.getInstance(mContext);
                    database.insertOrReplaceMessage(mPlus);
                }
                handler.onSuccess(mPlus);
            }

            @Override
            public void onError(Exception error) {
                super.onError(error);
                handler.onError(error);
            }
        });
    }

    private synchronized void retrieveMessages(final String channelId, final String sinceId, final String beforeId, final MessageManagerResponseHandler handler) {
        QueryParameters params = (QueryParameters) mParameters.get(channelId).clone();
        params.put("since_id", sinceId);
        params.put("before_id", beforeId);
        mClient.retrieveMessagesInChannel(channelId, params, new MessageListResponseHandler() {
            @Override
            public void onSuccess(final MessageList responseData) {
                ADNDatabase database = ADNDatabase.getInstance(mContext);
                boolean appended = true;

                MinMaxPair minMaxPair = getMinMaxPair(channelId);
                if(beforeId != null && sinceId == null) {
                    String newMinId = getMinId();
                    if(newMinId != null) {
                        minMaxPair.minId = newMinId;
                    }
                } else if(beforeId == null && sinceId != null) {
                    appended = false;
                    String newMaxId = getMaxId();
                    if(newMaxId != null) {
                        minMaxPair.maxId = newMaxId;
                    }
                } else if(beforeId == null && sinceId == null) {
                    minMaxPair.minId = getMinId();
                    minMaxPair.maxId = getMaxId();
                }

                LinkedHashMap<String, MessagePlus> channelMessages = mMessages.get(channelId);
                if(channelMessages == null) {
                    channelMessages = new LinkedHashMap<String, MessagePlus>(responseData.size());
                    mMessages.put(channelId, channelMessages);
                }

                ArrayList<MessagePlus> newestMessages = new ArrayList<MessagePlus>(responseData.size());
                LinkedHashMap<String, MessagePlus> newFullChannelMessagesMap = new LinkedHashMap<String, MessagePlus>(channelMessages.size() + responseData.size());

                if(appended) {
                    newFullChannelMessagesMap.putAll(channelMessages);
                }
                for(Message m : responseData) {
                    MessagePlus mPlus = new MessagePlus(m);
                    newestMessages.add(mPlus);
                    adjustDateAndInsert(mPlus, database);

                    newFullChannelMessagesMap.put(m.getId(), mPlus);
                }
                if(!appended) {
                    newFullChannelMessagesMap.putAll(channelMessages);
                }
                mMessages.put(channelId, newFullChannelMessagesMap);

                if(mConfiguration.isLocationLookupEnabled) {
                    lookupLocation(newestMessages);
                }

                if(handler != null) {
                    handler.onSuccess(newestMessages, appended);
                }
            }

            @Override
            public void onError(Exception error) {
                Log.d(TAG, error.getMessage(), error);

                if(handler != null) {
                    handler.onError(error);
                }
            }
        });
    }

    private void adjustDateAndInsert(MessagePlus mPlus, ADNDatabase database) {
        Date adjustedDate = getAdjustedDate(mPlus.getMessage());
        mPlus.setDisplayDate(adjustedDate);
        if(mConfiguration.isDatabaseInsertionEnabled) {
            database.insertOrReplaceMessage(mPlus);
            database.insertOrReplaceHashtagInstances(mPlus);
        }
    }

    private Date getAdjustedDate(Message message) {
        return mConfiguration.dateAdapter == null ? message.getCreatedAt() : mConfiguration.dateAdapter.getDisplayDate(message);
    }

    public static class MessageManagerConfiguration {

        public static interface MessageLocationLookupHandler {
            public void onSuccess(MessagePlus messagePlus);
            public void onException(MessagePlus messagePlus, Exception exception);
        }

        boolean isDatabaseInsertionEnabled;
        boolean isLocationLookupEnabled;
        MessageDisplayDateAdapter dateAdapter;
        MessageLocationLookupHandler locationLookupHandler;

        /**
         * Enable or disable automatic insertion of Messages into a sqlite database
         * upon retrieval. By default, this feature is turned off.
         *
         * @param isEnabled true if all retrieved Messages should be stashed in a sqlite
         *                  database, false otherwise.
         */
        public void setDatabaseInsertionEnabled(boolean isEnabled) {
            this.isDatabaseInsertionEnabled = isEnabled;
        }

        /**
         * Set a MessageDisplayDateAdapter.
         *
         * @param adapter
         * @see com.alwaysallthetime.adnlibutils.manager.MessageManager.MessageDisplayDateAdapter
         */
        public void setMessageDisplayDateAdapter(MessageDisplayDateAdapter adapter) {
            this.dateAdapter = adapter;
        }

        /**
         * Enable location lookup on Messages. If enabled, annotations will be examined in order
         * to construct a DisplayLocation. A DisplayLocation will be set on the associated MessagePlus
         * Object, based off one of these three annotations, if they exist:
         *
         * net.app.core.checkin
         * net.app.ohai.location
         * net.app.core.geolocation
         *
         * In the case of net.app.core.geolocation, an asynchronous task will be fired off to
         * perform reverse geolocation on the latitude/longitude coordinates. For this reason, you
         * should set a MessageLocationLookupHandler on this configuration if you want to perform
         * a task such as update UI after a location is obtained.
         *
         * If none of these annotations is found, then a null DisplayLocation is set on the
         * associated MessagePlus.
         *
         * @param isEnabled true if location lookup should be performed on all Messages
         *
         * @see com.alwaysallthetime.adnlibutils.MessagePlus#getDisplayLocation()
         * @see com.alwaysallthetime.adnlibutils.MessagePlus#hasSetDisplayLocation()
         * @see com.alwaysallthetime.adnlibutils.MessagePlus#hasDisplayLocation()
         */
        public void setLocationLookupEnabled(boolean isEnabled) {
            this.isLocationLookupEnabled = isEnabled;
        }

        /**
         * Specify a handler to be notified when location lookup has completed for a MessagePlus.
         * This is particularly useful when a geolocation annotation requires an asynchronous
         * reverse geocoding task.
         *
         * @param handler
         */
        public void setLocationLookupHandler(MessageLocationLookupHandler handler) {
            this.locationLookupHandler = handler;
        }
    }
}

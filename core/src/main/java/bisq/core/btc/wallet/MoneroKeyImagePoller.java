package bisq.core.btc.wallet;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import monero.common.MoneroError;

/**
 * Listen for changes to the spent status of key images.
 */
public class MoneroKeyImagePoller {

    private long refreshPeriodMs;
    private Set<String> keyImages = new HashSet<String>();
    private Set<MoneroKeyImageListener> listeners = new HashSet<MoneroKeyImageListener>();

    /**
     * Construct the listener.
     * 
     * @param refreshPeriodMs - refresh period in milliseconds
     * @param keyImages - key images to listen to
     */
    public MoneroKeyImagePoller(long refreshPeriodMs, String... keyImages) {
        this.refreshPeriodMs = refreshPeriodMs;
        setKeyImages(keyImages);
    }

    /**
     * Add a listener to receive notifications.
     * 
     * @param listener - the listener to add
     */
    public void addListener(MoneroKeyImageListener listener) {
      listeners.add(listener);
      refreshPolling();
    }
    
    /**
     * Remove a listener to receive notifications.
     * 
     * @param listener - the listener to remove
     */
    public void removeListener(MoneroKeyImageListener listener) {
      if (!listeners.contains(listener)) throw new MoneroError("Listener is not registered");
      listeners.remove(listener);
      refreshPolling();
    }

    /**
     * Set the refresh period in milliseconds.
     * 
     * @param refreshPeriodMs - the refresh period in milliseconds
     */
    public void setRefreshPeriodMs(long refreshPeriodMs) {
        this.refreshPeriodMs = refreshPeriodMs;
    }

    /**
     * Get the refresh period in milliseconds
     * 
     * @return the refresh period in milliseconds
     */
    public long getRefreshPeriodMs() {
        return refreshPeriodMs;
    }

    /**
     * Get the key images to listen to.
     * 
     * @return the key images to listen to
     */
    public Set<String> getKeyImages() {
        return keyImages;
    }

    /**
     * Set the key images to listen to.
     * 
     * @return the key images to listen to
     */
    public void setKeyImages(String... keyImages) {
        this.keyImages.clear();
        this.keyImages.addAll(Arrays.asList(keyImages));
        refreshPolling();
    }

    /**
     * Add a key image to listen to.
     * 
     * @param keyImage - the key image to listen to
     */
    public void addKeyImage(String keyImage) {
        addKeyImages(keyImage);
        refreshPolling();
    }

    /**
     * Add key images to listen to.
     * 
     * @param keyImages - key images to listen to
     */
    public void addKeyImages(String... keyImages) {
        this.keyImages.addAll(Arrays.asList(keyImages));
        refreshPolling();
    }

    /**
     * Remove a key image to listen to.
     * 
     * @param keyImage - the key image to unlisten to
     */
    public void removeKeyImage(String keyImage) {
        removeKeyImages(keyImage);
        refreshPolling();
    }

    /**
     * Remove key images to listen to.
     * 
     * @param keyImages - key images to unlisten to
     */
    public void removeKeyImages(String... keyImages) {
        for (String keyImage : keyImages) if (!this.keyImages.contains(keyImage)) throw new MoneroError("Key image not registered with poller: " + keyImage);
        this.keyImages.removeAll(Arrays.asList(keyImages));
    }

    public void poll() {
        throw new RuntimeException("Not implemented");
    }

    private void refreshPolling() {
        throw new RuntimeException("Not implemented");
    }
}

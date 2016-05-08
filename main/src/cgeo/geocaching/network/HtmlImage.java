package cgeo.geocaching.network;

import cgeo.geocaching.CgeoApplication;
import cgeo.geocaching.R;
import cgeo.geocaching.compatibility.Compatibility;
import cgeo.geocaching.connector.ConnectorFactory;
import cgeo.geocaching.storage.LocalStorage;
import cgeo.geocaching.utils.AndroidRxUtils;
import cgeo.geocaching.utils.CancellableHandler;
import cgeo.geocaching.utils.FileUtils;
import cgeo.geocaching.utils.ImageUtils;
import cgeo.geocaching.utils.ImageUtils.ContainerDrawable;
import cgeo.geocaching.utils.Log;
import cgeo.geocaching.utils.RxUtils.ObservableCache;

import okhttp3.Response;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jdt.annotation.Nullable;
import rx.Completable;
import rx.Observable;
import rx.Observable.OnSubscribe;
import rx.Subscriber;
import rx.functions.Action0;
import rx.functions.Func0;
import rx.functions.Func1;
import rx.subjects.PublishSubject;
import rx.subscriptions.CompositeSubscription;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Point;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.text.Html;
import android.widget.TextView;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.util.HashMap;
import java.util.Map;

/**
 * All-purpose image getter that can also be used as a ImageGetter interface when displaying caches.
 */

public class HtmlImage implements Html.ImageGetter {

    private static final String[] BLOCKED = {
            "gccounter.de",
            "gccounter.com",
            "cachercounter/?",
            "gccounter/imgcount.php",
            "flagcounter.com",
            "compteur-blog.net",
            "counter.digits.com",
            "andyhoppe",
            "besucherzaehler-homepage.de",
            "hitwebcounter.com",
            "kostenloser-counter.eu",
            "trendcounter.com",
            "hit-counter-download.com",
            "gcwetterau.de/counter"
    };
    public static final String SHARED = "shared";

    @NonNull private final String geocode;
    /**
     * on error: return large error image, if {@code true}, otherwise empty 1x1 image
     */
    private final boolean returnErrorImage;
    private final boolean onlySave;
    private final boolean userInitiatedRefresh;
    private final int maxWidth;
    private final int maxHeight;
    private final Resources resources;
    protected final TextView view;
    private final Map<String, BitmapDrawable> cache = new HashMap<>();

    private final ObservableCache<String, BitmapDrawable> observableCache = new ObservableCache<>(new Func1<String, Observable<BitmapDrawable>>() {
        @Override
        public Observable<BitmapDrawable> call(final String url) {
            return fetchDrawableUncached(url);
        }
    });

    // Background loading
    // .cache() is not yet available on Completable instances as of RxJava 1.1.1, so we have to go back
    // to the observable world to achieve the caching.
    private final PublishSubject<Completable> loading = PublishSubject.create();
    private final Completable waitForEnd = Completable.merge(loading).toObservable().cache().toCompletable();
    private final CompositeSubscription subscription = new CompositeSubscription(waitForEnd.subscribe());

    /**
     * Create a new HtmlImage object with different behaviors depending on <tt>onlySave</tt> and <tt>view</tt> values.
     * There are the three possible use cases:
     * <ul>
     * <li>If onlySave is true, {@link #getDrawable(String)} will return <tt>null</tt> immediately and will queue the
     * image retrieval and saving in the loading subject. Downloads will start in parallel when the blocking
     * {@link #waitForEndCompletable(CancellableHandler)} method is called, and they can be
     * cancelled through the given handler.</li>
     * <li>If <tt>onlySave</tt> is <tt>false</tt> and the instance is called through {@link #fetchDrawable(String)},
     * then an observable for the given URL will be returned. This observable will emit the local copy of the image if
     * it is present regardless of its freshness, then if needed an updated fresher copy after retrieving it from the
     * network.</li>
     * <li>If <tt>onlySave</tt> is <tt>false</tt> and the instance is used as an {@link android.text.Html.ImageGetter},
     * only the final version of the image will be returned, unless a view has been provided. If it has, then a dummy
     * drawable is returned and is updated when the image is available, possibly several times if we had a stale copy of
     * the image and then got a new one from the network.</li>
     * </ul>
     *
     * @param geocode
     *            the geocode of the item for which we are requesting the image, or {@link #SHARED} to use the shared
     *            cache directory
     * @param returnErrorImage
     *            set to <tt>true</tt> if an error image should be returned in case of a problem, <tt>false</tt> to get
     *            a transparent 1x1 image instead
     * @param onlySave
     *            if set to <tt>true</tt>, {@link #getDrawable(String)} will only fetch and store the image, not return
     *            it
     * @param view
     *            if non-null, {@link #getDrawable(String)} will return an initially empty drawable which will be
     *            redrawn when the image is ready through an invalidation of the given view
     * @param userInitiatedRefresh
     *            if `true`, even fresh images will be refreshed if they have changed
     */
    public HtmlImage(@NonNull final String geocode, final boolean returnErrorImage, final boolean onlySave,
                     final TextView view, final boolean userInitiatedRefresh) {
        this.geocode = geocode;
        this.returnErrorImage = returnErrorImage;
        this.onlySave = onlySave;
        this.view = view;
        this.userInitiatedRefresh = userInitiatedRefresh;

        final Point displaySize = Compatibility.getDisplaySize();
        this.maxWidth = displaySize.x - 25;
        this.maxHeight = displaySize.y - 25;
        this.resources = CgeoApplication.getInstance().getResources();
    }

    /**
     * Create a new HtmlImage object with different behaviors depending on <tt>onlySave</tt> value. No view object
     * will be tied to this HtmlImage.
     *
     * For documentation, see {@link #HtmlImage(String, boolean, boolean, TextView, boolean)}.
     */
    public HtmlImage(@NonNull final String geocode, final boolean returnErrorImage, final boolean onlySave,
                     final boolean userInitiatedRefresh) {
        this(geocode, returnErrorImage, onlySave, null, userInitiatedRefresh);
    }

    /**
     * Retrieve and optionally display an image.
     * See {@link #HtmlImage(String, boolean, boolean, TextView, boolean)} for the various behaviours.
     *
     * @param url
     *            the URL to fetch from cache or network
     * @return a drawable containing the image, or <tt>null</tt> if <tt>onlySave</tt> is <tt>true</tt>
     */
    @Nullable
    @Override
    public BitmapDrawable getDrawable(final String url) {
        if (cache.containsKey(url)) {
            return cache.get(url);
        }
        final Observable<BitmapDrawable> drawable = fetchDrawable(url);
        if (onlySave) {
            loading.onNext(drawable.toCompletable());
            cache.put(url, null);
            return null;
        }
        final BitmapDrawable result = view == null ? drawable.toBlocking().lastOrDefault(null) : getContainerDrawable(drawable);
        cache.put(url, result);
        return result;
    }

    protected BitmapDrawable getContainerDrawable(final Observable<BitmapDrawable> drawable) {
        return new ContainerDrawable(view, drawable);
    }

    public Observable<BitmapDrawable> fetchDrawable(final String url) {
        return observableCache.get(url);
    }

    // Caches are loaded from disk on a computation scheduler to avoid using more threads than cores while decoding
    // the image. Downloads happen on downloadScheduler, in parallel with image decoding.
    private Observable<BitmapDrawable> fetchDrawableUncached(final String url) {
        if (StringUtils.isBlank(url) || ImageUtils.containsPattern(url, BLOCKED)) {
            return Observable.just(ImageUtils.getTransparent1x1Drawable(resources));
        }

        // Explicit local file URLs are loaded from the filesystem regardless of their age. The IO part is short
        // enough to make the whole operation on the computation scheduler.
        if (FileUtils.isFileUrl(url)) {
            return Observable.defer(new Func0<Observable<BitmapDrawable>>() {
                @Override
                public Observable<BitmapDrawable> call() {
                    final Bitmap bitmap = loadCachedImage(FileUtils.urlToFile(url), true).left;
                    return bitmap != null ? Observable.just(ImageUtils.scaleBitmapToFitDisplay(bitmap)) : Observable.<BitmapDrawable>empty();
                }
            }).subscribeOn(AndroidRxUtils.computationScheduler);
        }

        final boolean shared = url.contains("/images/icons/icon_");
        final String pseudoGeocode = shared ? SHARED : geocode;

        return Observable.create(new OnSubscribe<BitmapDrawable>() {
            @Override
            public void call(final Subscriber<? super BitmapDrawable> subscriber) {
                subscription.add(subscriber);
                subscriber.add(AndroidRxUtils.computationScheduler.createWorker().schedule(new Action0() {
                    @Override
                    public void call() {
                        final ImmutablePair<BitmapDrawable, Boolean> loaded = loadFromDisk();
                        final BitmapDrawable bitmap = loaded.left;
                        if (loaded.right) {
                            subscriber.onNext(bitmap);
                            subscriber.onCompleted();
                            return;
                        }
                        if (bitmap != null && !onlySave) {
                            subscriber.onNext(bitmap);
                        }
                        AndroidRxUtils.networkScheduler.createWorker().schedule(new Action0() {
                            @Override public void call() {
                                downloadAndSave(subscriber);
                            }
                        });
                    }
                }));
            }

            private ImmutablePair<BitmapDrawable, Boolean> loadFromDisk() {
                final ImmutablePair<Bitmap, Boolean> loadResult = loadImageFromStorage(url, pseudoGeocode, shared);
                return scaleImage(loadResult);
            }

            private void downloadAndSave(final Subscriber<? super BitmapDrawable> subscriber) {
                final File file = LocalStorage.getStorageFile(pseudoGeocode, url, true, true);
                if (url.startsWith("data:image/")) {
                    if (url.contains(";base64,")) {
                        ImageUtils.decodeBase64ToFile(StringUtils.substringAfter(url, ";base64,"), file);
                    } else {
                        Log.e("HtmlImage.getDrawable: unable to decode non-base64 inline image");
                        subscriber.onCompleted();
                        return;
                    }
                } else if (subscriber.isUnsubscribed() || downloadOrRefreshCopy(url, file)) {
                        // The existing copy was fresh enough or we were unsubscribed earlier.
                        subscriber.onCompleted();
                        return;
                }
                if (onlySave) {
                    subscriber.onCompleted();
                    return;
                }
                AndroidRxUtils.computationScheduler.createWorker().schedule(new Action0() {
                    @Override
                    public void call() {
                        final ImmutablePair<BitmapDrawable, Boolean> loaded = loadFromDisk();
                        final BitmapDrawable image = loaded.left;
                        if (image != null) {
                            subscriber.onNext(image);
                        } else {
                            subscriber.onNext(returnErrorImage ?
                                    new BitmapDrawable(resources, BitmapFactory.decodeResource(resources, R.drawable.image_not_loaded)) :
                                    ImageUtils.getTransparent1x1Drawable(resources));
                        }
                        subscriber.onCompleted();
                    }
                });
            }
        });
    }

    @SuppressWarnings("static-method")
    protected ImmutablePair<BitmapDrawable, Boolean> scaleImage(final ImmutablePair<Bitmap, Boolean> loadResult) {
        final Bitmap bitmap = loadResult.left;
        return ImmutablePair.of(bitmap != null ? ImageUtils.scaleBitmapToFitDisplay(bitmap) : null, loadResult.right);
    }

    public Completable waitForEndCompletable(@Nullable final CancellableHandler handler) {
        if (handler != null) {
            handler.unsubscribeIfCancelled(subscription);
        }
        loading.onCompleted();
        return waitForEnd;
    }

    /**
     * Download or refresh the copy of {@code url} in {@code file}.
     *
     * @param url the url of the document
     * @param file the file to save the document in
     * @return {@code true} if the existing file was up-to-date, {@code false} otherwise
     */
    private boolean downloadOrRefreshCopy(final String url, final File file) {
        final String absoluteURL = makeAbsoluteURL(url);

        if (absoluteURL != null) {
            try {
                final Response httpResponse = Network.getRequest(absoluteURL, null, file).toBlocking().value();
                if (httpResponse.isSuccessful()) {
                    LocalStorage.saveEntityToFile(httpResponse, file);
                } else if (httpResponse.code() == 304) {
                    if (!file.setLastModified(System.currentTimeMillis())) {
                        makeFreshCopy(file);
                    }
                    return true;
                }
            } catch (final Exception e) {
                Log.e("HtmlImage.downloadOrRefreshCopy", e);
            }
        }
        return false;
    }

    /**
     * Make a fresh copy of the file to reset its timestamp. On some storage, it is impossible
     * to modify the modified time after the fact, in which case a brand new file must be
     * created if we want to be able to use the time as validity hint.
     *
     * See Android issue 1699.
     *
     * @param file the file to refresh
     */
    private static void makeFreshCopy(final File file) {
        final File tempFile = new File(file.getParentFile(), file.getName() + "-temp");
        if (file.renameTo(tempFile)) {
            LocalStorage.copy(tempFile, file);
            FileUtils.deleteIgnoringFailure(tempFile);
        } else {
            Log.e("Could not reset timestamp of file " + file.getAbsolutePath());
        }
    }

    /**
     * Load an image from primary or secondary storage.
     *
     * @param url the image URL
     * @param pseudoGeocode the geocode or the shared name
     * @param forceKeep keep the image if it is there, without checking its freshness
     * @return A pair whose first element is the bitmap if available, and the second one is {@code true} if the image is present and fresh enough.
     */
    @NonNull
    private ImmutablePair<Bitmap, Boolean> loadImageFromStorage(final String url, @NonNull final String pseudoGeocode, final boolean forceKeep) {
        try {
            final File file = LocalStorage.getStorageFile(pseudoGeocode, url, true, false);
            final ImmutablePair<Bitmap, Boolean> image = loadCachedImage(file, forceKeep);
            if (image.right || image.left != null) {
                return image;
            }
            final File fileSec = LocalStorage.getStorageSecFile(pseudoGeocode, url, true);
            return loadCachedImage(fileSec, forceKeep);
        } catch (final Exception e) {
            Log.w("HtmlImage.loadImageFromStorage", e);
        }
        return ImmutablePair.of((Bitmap) null, false);
    }

    @Nullable
    private String makeAbsoluteURL(final String url) {
        // Check if uri is absolute or not, if not attach the connector hostname
        // FIXME: that should also include the scheme
        if (Uri.parse(url).isAbsolute()) {
            return url;
        }

        final String host = ConnectorFactory.getConnector(geocode).getHost();
        if (StringUtils.isNotEmpty(host)) {
            final StringBuilder builder = new StringBuilder("http://");
            builder.append(host);
            if (!StringUtils.startsWith(url, "/")) {
                // FIXME: explain why the result URL would be valid if the path does not start with
                // a '/', or signal an error.
                builder.append('/');
            }
            builder.append(url);
            return builder.toString();
        }

        return null;
    }

    /**
     * Load a previously saved image.
     *
     * @param file the file on disk
     * @param forceKeep keep the image if it is there, without checking its freshness
     * @return a pair with {@code true} in the second component if the image was there and is fresh enough or {@code false} otherwise,
     *         and the image (possibly {@code null} if the second component is {@code false} and the image
     *         could not be loaded, or if the second component is {@code true} and {@code onlySave} is also
     *         {@code true})
     */
    @NonNull
    private ImmutablePair<Bitmap, Boolean> loadCachedImage(final File file, final boolean forceKeep) {
        // An image is considered fresh enough if the image exists and one of those conditions is true:
        //  - forceKeep is true and the image has not been modified in the last 24 hours, to avoid reloading shared images;
        //    with every refreshed cache;
        //  - forceKeep is true and userInitiatedRefresh is false, as shared images are unlikely to change at all;
        //  - userInitiatedRefresh is false and the image has not been modified in the last 24 hours.
        if (file.exists()) {
            final boolean recentlyModified = file.lastModified() > (System.currentTimeMillis() - (24 * 60 * 60 * 1000));
            final boolean freshEnough = (forceKeep && (recentlyModified || !userInitiatedRefresh)) ||
                    (recentlyModified && !userInitiatedRefresh);
            if (freshEnough && onlySave) {
                return ImmutablePair.of((Bitmap) null, true);
            }
            final BitmapFactory.Options bfOptions = new BitmapFactory.Options();
            bfOptions.inTempStorage = new byte[16 * 1024];
            bfOptions.inPreferredConfig = Bitmap.Config.RGB_565;
            setSampleSize(file, bfOptions);
            final Bitmap image = BitmapFactory.decodeFile(file.getPath(), bfOptions);
            if (image == null) {
                Log.e("Cannot decode bitmap from " + file.getPath());
                return ImmutablePair.of((Bitmap) null, false);
            }
            return ImmutablePair.of(image, freshEnough);
        }
        return ImmutablePair.of((Bitmap) null, false);
    }

    private void setSampleSize(final File file, final BitmapFactory.Options bfOptions) {
        //Decode image size only
        final BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;

        BufferedInputStream stream = null;
        try {
            stream = new BufferedInputStream(new FileInputStream(file));
            BitmapFactory.decodeStream(stream, null, options);
        } catch (final FileNotFoundException e) {
            Log.e("HtmlImage.setSampleSize", e);
        } finally {
            IOUtils.closeQuietly(stream);
        }

        int scale = 1;
        if (options.outHeight > maxHeight || options.outWidth > maxWidth) {
            scale = Math.max(options.outHeight / maxHeight, options.outWidth / maxWidth);
        }
        bfOptions.inSampleSize = scale;
    }

}

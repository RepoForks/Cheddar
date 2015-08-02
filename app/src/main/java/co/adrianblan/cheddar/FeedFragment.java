package co.adrianblan.cheddar;

import android.graphics.Bitmap;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.support.v7.graphics.Palette;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.ListView;
import android.widget.ProgressBar;

import com.amulyakhare.textdrawable.TextDrawable;
import com.firebase.client.DataSnapshot;
import com.firebase.client.Firebase;
import com.firebase.client.FirebaseError;
import com.firebase.client.ValueEventListener;
import com.nostra13.universalimageloader.core.ImageLoader;
import com.nostra13.universalimageloader.core.listener.SimpleImageLoadingListener;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Created by Adrian on 2015-07-28.
 */
public class FeedFragment extends Fragment {

    private FeedAdapter feedAdapter;

    // Stores the submission IDs used for the API
    private ArrayList<Long> submissionIDs;

    // Base URL for the hacker news API
    private Firebase baseUrl;

    // Sub URL used for gathering comments
    private Firebase itemUrl;

    // Sub URL used for different stories
    private Firebase storiesUrl;

    // Collection of AsyncTasks we use to keep them from overflowing
    private ArrayList<AsyncTask> asyncTasks;

    //Throttle submissions
    private Date lastSubmissionUpdate;
    private final int submissionUpdateTime = 3;
    private final int submissionUpdateNum = 15;

    public static FeedFragment newInstance() {
        FeedFragment f = new FeedFragment();
        Bundle b = new Bundle();
        f.setArguments(b);
        return f;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        setRetainInstance(true);

        // Init API stuff
        Firebase.setAndroidContext(getActivity());
        baseUrl = new Firebase("https://hacker-news.firebaseio.com/v0/");
        itemUrl = baseUrl.child("/item/");
        storiesUrl = baseUrl.child(getArguments().getString("url"));

        feedAdapter = new FeedAdapter(getActivity());
        asyncTasks = new ArrayList<AsyncTask>();
        lastSubmissionUpdate = new Date();

        // Gets all the submissions and populates the list with them
        updateSubmissions();
    }

    @Override
    public void onSaveInstanceState(Bundle savedState) {
        super.onSaveInstanceState(savedState);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        setHasOptionsMenu(true);

        View rootView = inflater.inflate(R.layout.fragment_feed, container,false);

        ListView listView = (ListView) rootView.findViewById(R.id.feed_list);
        listView.setAdapter(feedAdapter);

        //If we scroll to the end, we simply fetch more submissions
        listView.setOnScrollListener(new AbsListView.OnScrollListener() {

            private int preLast;

            @Override
            public void onScrollStateChanged(AbsListView view, int scrollState) {}

            @Override
            public void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount, int totalItemCount) {

                if (view.getId() == R.id.feed_list) {
                    final int lastItem = firstVisibleItem + visibleItemCount;
                    if (lastItem >= totalItemCount) {

                        Date d = new Date();
                        long seconds = (d.getTime() - lastSubmissionUpdate.getTime()) / 1000;

                        //to avoid multiple calls for last item
                        if (lastItem != preLast && lastItem >= submissionUpdateNum && seconds >= submissionUpdateTime) {
                            updateSubmissions();
                            preLast = lastItem;
                            lastSubmissionUpdate = d;
                        }
                    }
                }
            }
        });

        ProgressBar circle = new ProgressBar(getActivity());
        circle.setPadding(0, 80, 0, 80);
        circle.setIndeterminate(true);
        listView.addFooterView(circle);

        return rootView;
    }

    // Sometimes we just might want to reset all submissions
    public void resetSubmissions(){

        Date d = new Date();
        long seconds = (d.getTime() - lastSubmissionUpdate.getTime()) / 1000;

        //We dont want to be able to spam resets
        if(feedAdapter.getCount() > 0 && seconds >= submissionUpdateTime) {

            //First we need to cancel all asynctasks
            while(!asyncTasks.isEmpty()){

                // Cancel all not finished tasks
                if(!asyncTasks.get(0).getStatus().equals(AsyncTask.Status.FINISHED)){
                    asyncTasks.get(0).cancel(true);
                }

                asyncTasks.remove(0);
            }

            // We also cancel all image fetching
            ImageLoader.getInstance().stop();

            submissionIDs = null;
            feedAdapter.clear();
            feedAdapter.notifyDataSetChanged();
            updateSubmissions();

            lastSubmissionUpdate = d;
        }
    }

    // Fetches a large number of submissions, and updates them individually
    public void updateSubmissions(){

        // If we don't have submissions loaded, we must first load them
        if(submissionIDs == null) {

            // Updates the list of 500 submission IDs
            storiesUrl.addListenerForSingleValueEvent(new ValueEventListener() {

                @Override
                public void onDataChange(DataSnapshot snapshot) {
                    submissionIDs = (ArrayList<Long>) snapshot.getValue();

                    // Because we are doing this asynchronously, it's easier to update submissions directly
                    updateSubmissions();
                }

                @Override
                public void onCancelled(FirebaseError firebaseError) {
                    System.err.println("Could not retrieve posts! " + firebaseError);
                }
            });
        } else {

            // From the top 500 submissions, we only load a few at a time
            for (int i = feedAdapter.getCount(); i < feedAdapter.getCount() + submissionUpdateNum && i < submissionIDs.size(); i++) {

                // But we must first add each submission to the view manually
                updateSingleSubmission(baseUrl.child("/item/" + submissionIDs.get(i)));
            }
        }
    }

    // Gets an url to a single submission and updates it in the feedadapter
    public void updateSingleSubmission(Firebase submission){

        submission.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {

                // We retrieve all objects into a hashmap
                Map<String, Object> ret = (Map<String, Object>) snapshot.getValue();

                if(ret == null){
                    return;
                }

                String url = (String) ret.get("url");
                if(url == ""){url = "http://hackernews.com";}

                URL site = null;
                try {
                    site = new URL(url);
                } catch (MalformedURLException e) {
                    System.err.println(e);
                    return;
                }

                FeedItem f = initNewFeedItem(ret, site);
                feedAdapter.add(f);
                feedAdapter.notifyDataSetChanged();

                if(!url.contains("hackernews.com")) {
                    // Asynchronously updates images for the feed item
                    updateSubmissionThumbnail(site.getHost(), f);
                    updateSubmissionFavicon(site.getHost(), f);
                }
            }

            @Override
            public void onCancelled(FirebaseError firebaseError) {
                System.err.println("Could not retrieve post! " + firebaseError);
            }
        });
    }

    // Takes the raw API data and the URL, returns a new feed item
    public FeedItem initNewFeedItem(Map<String, Object> ret, URL site){

        FeedItem f = new FeedItem();

        // Gets readable date
        Date past = new Date((Long) ret.get("time") * 1000);
        Date now = new Date();
        String time = getPrettyDate(past, now);

        int comments = 0;
        ArrayList<Long> kids = (ArrayList<Long>) ret.get("kids");
        f.setKids(kids);

        if(kids != null) {
            updateCommentCount(f, kids);
        }

        // Set titles and other data
        f.setTitle((String) ret.get("title"));
        f.setScore((Long) ret.get("score"));
        f.setTime(time);

        String domain = site.getHost().replace("www.", "");
        f.setShortUrl(domain);
        f.setLongUrl(site.toString());

        // We show the first letter of the url on the thumbnail
        if(domain.equals("hackernews.com")){
            f.setLetter("HN");
        } else {
            f.setLetter(domain.substring(0, 1));
        }

        // Generate TextDrawable thumbnail
        TextDrawable.IShapeBuilder builder = TextDrawable.builder().beginConfig().bold().toUpperCase().endConfig();
        TextDrawable drawable = builder.buildRect(f.getLetter(), getActivity().getResources().getColor(R.color.colorPrimary));
        f.setTextDrawable(drawable);
        f.setColor(getActivity().getResources().getColor(R.color.colorPrimary));

        return f;
    }

    // Goes through each comment for children and adds them to the count
    // Since traversing all comments takes a lot of work, we do it in a separate task
    public void updateCommentCount(FeedItem feedItem, ArrayList<Long> kids){

        final FeedItem f = feedItem;
        final ArrayList<Long> k = kids;

        feedItem.addCommentCount(kids.size());
        feedAdapter.notifyDataSetChanged();

        class commentUpdateTask extends AsyncTask<String, Integer, String> {

            @Override
            protected String doInBackground(String... url) {
                for(Long comment : k) {
                    updateSingleCommentCount(f, comment);
                }
                return null;
            }
            @Override
            protected void onProgressUpdate(Integer... i) {}

            @Override
            protected void onPostExecute(String thumbnailUrl) {
                feedAdapter.notifyDataSetChanged();
            }
        }

        commentUpdateTask task = new commentUpdateTask();
        asyncTasks.add(task);

        // TODO use threadpoolexecutor?
        task.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    // Recursively update comment count
    public void updateSingleCommentCount(FeedItem f, Long id){

        final FeedItem fi = f;

        itemUrl.child(Long.toString(id)).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {

                if (snapshot.getValue() == null) {
                    return;
                }

                ArrayList<Long> kids = (ArrayList<Long>) ((Map<String, Object>) snapshot.getValue()).get("kids");

                // Update child comments
                if (kids != null) {

                    fi.addCommentCount(kids.size());
                    //feedAdapter.notifyDataSetChanged();

                    for (int i = 0; i < kids.size(); i++) {
                        updateSingleCommentCount(fi, kids.get(i));
                    }
                }
            }

            @Override
            public void onCancelled(FirebaseError firebaseError) {
                System.err.println("Could not retrieve post! " + firebaseError);
            }
        });
    }

    // Recieves a host url, and the position of the feed item
    // Fetches a remote server for the url to the best thumbnail to use
    public void updateSubmissionThumbnail(String url, FeedItem f){

        final FeedItem fi = f;

        class ThumbnailUrlTask extends AsyncTask<String, Integer, String> {

            @Override
            protected String doInBackground(String... url) {
                // Gets url to a good thumbnail from a site
                ThumbnailExtractor ts = new ThumbnailExtractor();
                return ts.getThumbnailUrl(url[0]);
            }
            @Override
            protected void onProgressUpdate(Integer... i) {
            }

            @Override
            protected void onPostExecute(String thumbnailUrl) {

                // Loads high resolution icon
                ImageLoader imageLoader = ImageLoader.getInstance();

                imageLoader.loadImage(thumbnailUrl, new SimpleImageLoadingListener() {
                    @Override
                    public void onLoadingComplete(String url, View view, Bitmap loadedImage) {

                        if(loadedImage == null){
                            return;
                        }

                        // It's possible the list has changed during the async task
                        // So we make sure the item still exists
                        int position = feedAdapter.getPosition(fi);
                        if(position == -1){
                            return;
                        }

                        feedAdapter.getItem(position).setThumbnail(loadedImage);
                        feedAdapter.notifyDataSetChanged();
                    }
                });
            }
        }

        // Asynchronously fetches the URL to the thumbnail
        // We cannot use execute() since it only allows one thread at a time
        ThumbnailUrlTask task = new ThumbnailUrlTask();
        asyncTasks.add(task);

        // TODO use threadpoolexecutor?
        task.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, "http://icons.better-idea.org/api/icons?url=" + url);
    }

    // Recieves a host url, and the position of the feed item
    // Updates a low resolution thumbnail for that submission
    public void updateSubmissionFavicon(String url, FeedItem f){

        final FeedItem fi = f;
        ImageLoader imageLoader = ImageLoader.getInstance();

        // Loads low resolution favicon
        imageLoader.loadImage("http://www.google.com/s2/favicons?domain=" + url, new SimpleImageLoadingListener() {
            @Override
            public void onLoadingComplete(String url, View view, Bitmap loadedImage) {

                //We don't want to show the default image
                if (hashBitmap(loadedImage) == 56355950541L) {
                    return;
                }

                // Return if we already have a high resolution thumbnail
                if(fi.getThumbnail() != null){
                    return;
                }

                // It's possible the list has changed during the async task
                // So we make sure the item still exists
                int position = feedAdapter.getPosition(fi);
                if (position == -1) {
                    return;
                }

                // Generate lots of palettes from the favicon asynchronously
                Palette.from(loadedImage).generate(new Palette.PaletteAsyncListener() {
                    public void onGenerated(Palette p) {

                        List<Palette.Swatch> swatches = p.getSwatches();
                        Palette.Swatch vibrantSwatch = p.getVibrantSwatch();

                        TextDrawable drawable;
                        TextDrawable.IShapeBuilder builder = TextDrawable.builder().beginConfig().bold().toUpperCase().endConfig();

                        // We want the vibrant palette, if possible, ortherwise darker palettes
                        if (vibrantSwatch != null) {
                            drawable = builder.buildRect(fi.getLetter(), vibrantSwatch.getRgb());
                        } else if (!swatches.isEmpty()) {
                            drawable = builder.buildRect(fi.getLetter(), swatches.get(0).getRgb());
                        } else {
                            return;
                        }

                        fi.setTextDrawable(drawable);
                        feedAdapter.notifyDataSetChanged();
                    }
                });
            }
        });
    }

    // Converts the difference between two dates into a pretty date
    // There's probably a joke in there somewhere
    public String getPrettyDate(Date past, Date now){

        if(TimeUnit.MILLISECONDS.toDays(now.getTime() - past.getTime()) > 0){
            return TimeUnit.MILLISECONDS.toDays(now.getTime() - past.getTime()) + "d";
        } else if(TimeUnit.MILLISECONDS.toHours(now.getTime() - past.getTime()) > 0){
            return TimeUnit.MILLISECONDS.toHours(now.getTime() - past.getTime()) + "h";
        } if(TimeUnit.MILLISECONDS.toMinutes(now.getTime() - past.getTime()) > 0){
            return TimeUnit.MILLISECONDS.toMinutes(now.getTime() - past.getTime()) + "m";
        } else {
            return TimeUnit.MILLISECONDS.toSeconds(now.getTime() - past.getTime()) + "s";
        }
    }

    //This is the shittiest hash ever, but we only need it for one image so
    public long hashBitmap(Bitmap bmp){

        long hash = 0;
        for(int x = 0; x < bmp.getWidth(); x++){
            for (int y = 0; y < bmp.getHeight(); y++){
                hash += Math.abs(bmp.getPixel(x,y));
            }
        }
        return hash;
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {

        // We let each fragment create their own options menu
        // That way we can refresh the feeds individually
        inflater.inflate(R.menu.menu_main, menu);
        super.onCreateOptionsMenu(menu, inflater);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        if (id == R.id.refresh) {
            resetSubmissions();
        }

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        }

        return super.onOptionsItemSelected(item);
    }
}

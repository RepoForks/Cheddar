package co.adrianblan.cheddar;

import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.support.v7.app.ActionBar;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import com.amulyakhare.textdrawable.TextDrawable;
import com.firebase.client.DataSnapshot;
import com.firebase.client.Firebase;
import com.firebase.client.FirebaseError;
import com.firebase.client.ValueEventListener;
import com.github.ksoichiro.android.observablescrollview.ObservableListView;
import com.github.ksoichiro.android.observablescrollview.ObservableScrollViewCallbacks;
import com.github.ksoichiro.android.observablescrollview.ScrollState;

import java.util.ArrayList;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static android.widget.AdapterView.*;

/**
 * Created by Adrian on 2015-07-29.
 */
public class CommentActivity extends AppCompatActivity implements ObservableScrollViewCallbacks {

    private CommentAdapter commentAdapter;
    private ArrayList<Long> kids;
    private Date lastSubmissionUpdate;

    private FeedItem feedItem;
    private Long newCommentCount;
    private Bitmap thumbnail;

    private View header;
    private View no_comments;
    private View progress;

    // Base URL for the hacker news API
    private Firebase baseUrl;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_comment);

        // Init API stuff
        Firebase.setAndroidContext(this);
        baseUrl = new Firebase("https://hacker-news.firebaseio.com/v0/item/");

        if(savedInstanceState == null){
            Bundle b = getIntent().getExtras();
            feedItem = (FeedItem) b.getSerializable("feedItem");
            commentAdapter = new CommentAdapter(feedItem, this);
        } else {

            // We retrieve the saved items
            feedItem = (FeedItem) savedInstanceState.getSerializable("feedItem");
            ArrayList<Comment> comments = (ArrayList<Comment>) savedInstanceState.getSerializable("comments");
            commentAdapter = new CommentAdapter(comments, feedItem, this);
        }

        // Init toolbar
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar_comment);
        setSupportActionBar(toolbar);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        getSupportActionBar().setTitle(feedItem.getTitle());

        // Init list
        ObservableListView lv = (ObservableListView) findViewById(R.id.activity_comment_list);
        lv.setScrollViewCallbacks(this);
        lv.addHeaderView(initHeader(feedItem));
        lv.setAdapter(commentAdapter);
        addCommentOnClickListeners(lv);

        no_comments = (TextView) findViewById(R.id.activity_comment_none);
        progress = (LinearLayout) findViewById(R.id.activity_comment_progress);

        // Don't update if we already have retrieved saved comments
        if(commentAdapter.getCount() == 0) {
            updateComments();
        }
    }

    // Initializes the submission header with data
    public View initHeader(final FeedItem feedItem){

        header = View.inflate(this, R.layout.feed_item, null);

        TextView title = (TextView) header.findViewById(R.id.feed_item_title);
        title.setText(feedItem.getTitle());

        TextView subtitle = (TextView) header.findViewById(R.id.feed_item_shortUrl);
        subtitle.setText(feedItem.getShortUrl());

        TextView score = (TextView) header.findViewById(R.id.feed_item_score);
        score.setText(Long.toString(feedItem.getScore()));

        TextView comments = (TextView) header.findViewById(R.id.feed_item_comments);
        comments.setText(Long.toString(feedItem.getCommentCount()));

        TextView time = (TextView) header.findViewById(R.id.feed_item_time);
        time.setText(feedItem.getTime());

        thumbnail = (Bitmap) getIntent().getParcelableExtra("thumbnail");

        // Generate new TextDrawable
        ImageView imageView = (ImageView) header.findViewById(R.id.feed_item_thumbnail);
        if(thumbnail != null){
            imageView.setImageBitmap(thumbnail);
        } else if (feedItem.getTextDrawable() != null){
            imageView.setImageDrawable(feedItem.getTextDrawable());
        } else {
            // Generate TextDrawable if we don't have one
            TextDrawable.IShapeBuilder builder = TextDrawable.builder().beginConfig().bold().toUpperCase().endConfig();
            TextDrawable drawable = builder.buildRect(feedItem.getLetter(), feedItem.getColor());
            imageView.setImageDrawable(drawable);

            if(feedItem.getTextDrawable() == null){
                feedItem.setTextDrawable(drawable);
            }
        }

        // If the url doesn't go to hacker news
        if(feedItem.getLongUrl() != null){
            LinearLayout image_container = (LinearLayout) header.findViewById(R.id.feed_item_thumbnail_container);
            // If we click the thumbnail, get to the webview
            image_container.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Intent intent = new Intent(v.getContext(), WebViewActivity.class);
                    Bundle bundle = new Bundle();
                    bundle.putSerializable("feedItem", feedItem);
                    intent.putExtra("thumbnail", thumbnail);
                    intent.putExtras(bundle);
                    startActivity(intent);
                }
            });
        }

        //Add padding so that we compensate for the Toolbar
        updateHeaderPadding(true);

        return header;
    }

    // Adds onClickListeners for hiding and revealing comments
    public void addCommentOnClickListeners(ListView lv){

        // We listen to long clicks for hiding comments
        lv.setOnItemLongClickListener(new OnItemLongClickListener() {

            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                System.out.println("onItemClick");

                // If there is no comment data, or we clicked the header, return
                if (commentAdapter.getComments().size() == 0 || position == 0) {
                    return true;
                }

                Comment comment = commentAdapter.getItem(position - 1);
                int hierarchy = comment.getHierarchy();

                if (!comment.hasHideChildren()) {
                    int i;

                    // Find all child comments with higher hierarchy and hide them
                    // We do the direct access since we want to be able to access hidden comments
                    for (i = position; commentAdapter.getComments().get(i).getHierarchy() > hierarchy && i < commentAdapter.getComments().size(); i++) {
                        commentAdapter.getItem(i).setIsHidden(true);
                    }

                    int hiddenChildren = i - position;
                    comment.setHiddenChildren(hiddenChildren);

                    if (hiddenChildren > 0) {
                        comment.setHideChildren(true);
                    }

                    commentAdapter.notifyDataSetChanged();
                }

                // Nothing happened, don't consume the click
                return false;
            }
        });

        // And short clicks for revealing comments
        lv.setOnItemClickListener(new OnItemClickListener() {

            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {

                // If there is no comment data, or we clicked the header, return
                if (commentAdapter.getComments().size() == 0 || position == 0) {
                    return;
                }

                Comment comment = commentAdapter.getItem(position - 1);
                int hierarchy = comment.getHierarchy();

                if (comment.hasHideChildren()) {

                    comment.setHideChildren(false);
                    // Find all child comments with higher hierarchy and show them
                    for (int i = position; commentAdapter.getComments().get(i).getHierarchy() > hierarchy && i < commentAdapter.getComments().size(); i++) {
                        commentAdapter.getItem(i).setIsHidden(false);
                    }
                }
                commentAdapter.notifyDataSetChanged();
            }
        });
    }

    // Starts updating the commentCount from the top level
    public void updateComments(){

        if(lastSubmissionUpdate != null) {
            Date d = new Date();
            long seconds = (d.getTime() - lastSubmissionUpdate.getTime()) / 1000;

            // We want to throttle repeated refreshes
            if (seconds < 2) {
                return;
            }
        }

        lastSubmissionUpdate = new Date();

        newCommentCount = 0L;
        commentAdapter.clear();
        commentAdapter.notifyDataSetChanged();

        progress.setVisibility(View.VISIBLE);
        no_comments.setVisibility(View.GONE);

        baseUrl.child(Long.toString(feedItem.getSubmissionId())).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {

                // Hide the progress bar
                progress.setVisibility(View.GONE);

                // We retrieve all objects into a hashmap
                Map<String, Object> ret = (Map<String, Object>) snapshot.getValue();
                kids = (ArrayList<Long>) ret.get("kids");

                // Update the feed item data
                feedItem.setTime(getPrettyDate((long) ret.get("time")));
                feedItem.setScore((Long) ret.get("score"));

                if (kids != null) {

                    no_comments.setVisibility(View.GONE);
                    newCommentCount += kids.size();
                    updateHeader();

                    //TODO fix race condition
                    for (int i = 0; i < kids.size(); i++) {
                        updateSingleComment(kids.get(i), null);
                    }
                } else {
                    updateHeader();

                    //If we can't load any posts, we show a warning
                    no_comments.setVisibility(View.VISIBLE);
                }
            }

            @Override
            public void onCancelled(FirebaseError firebaseError) {
                System.err.println("Could not retrieve post! " + firebaseError);
                no_comments.setVisibility(View.VISIBLE);
                progress.setVisibility(View.GONE);
            }
        });
    }

    // Gets an url to a single comment
    public void updateSingleComment(Long id, Comment parent){

        final Comment par = parent;

        baseUrl.child(Long.toString(id)).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {

                // We retrieve all objects into a hashmap
                Map<String, Object> ret = (Map<String, Object>) snapshot.getValue();

                if (ret == null || ret.get("text") == null) {
                    return;
                }

                Comment com = new Comment();
                com.setBy((String) ret.get("by"));
                com.setBody((String) ret.get("text"));
                com.setTime(getPrettyDate((Long) ret.get("time")));

                // Check if top level comment
                if (par == null) {
                    com.setHierarchy(0);
                    commentAdapter.add(com);
                } else {
                    com.setHierarchy(par.getHierarchy() + 1);
                    commentAdapter.add(commentAdapter.getPosition(par) + 1, com);
                }

                // If we load a comment into a collapsed chain, we must hide it
                int position = commentAdapter.getPosition(com);
                if(position > 0){

                    Comment aboveComment = commentAdapter.getItem(position - 1);

                    if(aboveComment.isHidden() || (aboveComment.hasHideChildren() && aboveComment.getHierarchy() > com.getHierarchy())){
                        com.setIsHidden(true);
                    }
                }

                commentAdapter.notifyDataSetChanged();
                ArrayList<Long> kids = (ArrayList<Long>) ret.get("kids");

                // Update child commentCount
                if (kids != null) {

                    newCommentCount += kids.size();
                    updateHeader();

                    // We're counting backwards since we are too lazy to fix a race condition
                    //TODO fix race condition
                    for (int i = 1; i <= kids.size(); i++) {
                        updateSingleComment(kids.get(kids.size() - i), com);
                    }
                }
            }

            @Override
            public void onCancelled(FirebaseError firebaseError) {
                System.err.println("Could not retrieve post! " + firebaseError);
            }
        });
    }

    // Updates the header with new data
    public void updateHeader(){
        TextView scoreView = (TextView) header.findViewById(R.id.feed_item_score);
        scoreView.setText(Long.toString(feedItem.getScore()));

        if(newCommentCount > feedItem.getCommentCount()) {
            TextView commentView = (TextView) header.findViewById(R.id.feed_item_comments);
            commentView.setText(Long.toString(newCommentCount));
            feedItem.setCommentCount(newCommentCount);
        }

        TextView timeView = (TextView) header.findViewById(R.id.feed_item_time);
        timeView.setText(feedItem.getTime());
    }

    // Converts the difference between two dates into a pretty date
    // There's probably a joke in there somewhere
    public String getPrettyDate(Long time){

        Date past = new Date(time * 1000);
        Date now = new Date();

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

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater menuInflater = getMenuInflater();

        // If we don't have an Url, we make sure you can't go to the webview
        if(feedItem.getLongUrl() != null) {
            menuInflater.inflate(R.menu.menu_comments, menu);
        } else {
            menuInflater.inflate(R.menu.menu_main, menu);
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        if(id == android.R.id.home){
            onBackPressed();
            return true;
        }

        else if (id == R.id.menu_refresh) {
            updateComments();
        }

        else if(id == R.id.menu_webview){
            Intent intent = new Intent(this, WebViewActivity.class);
            Bundle b = new Bundle();
            b.putSerializable("feedItem", feedItem);
            intent.putExtra("thumbnail", thumbnail);
            intent.putExtras(b);
            startActivity(intent);
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onScrollChanged(int scrollY, boolean firstScroll, boolean dragging) {}

    @Override
    public void onDownMotionEvent() {}

    @Override
    public void onUpOrCancelMotionEvent(ScrollState scrollState) {
        ActionBar ab = getSupportActionBar();
        if (scrollState == ScrollState.UP) {
            if (ab.isShowing()) {
                ab.hide();
                updateHeaderPadding(false);
            }
        } else if (scrollState == ScrollState.DOWN) {
            if (!ab.isShowing()) {
                ab.show();
                updateHeaderPadding(true);
            }
        }
    }

    // Updates the padding on header to compensate for what is visible on the screen
    public void updateHeaderPadding(boolean show){
        if (show) {
            header.setPadding(0, (int) getResources().getDimension(R.dimen.toolbar_height), 0, 0);
        } else {
            // If we hide the toolbar, we need to reduce the padding to compensate
            header.setPadding(0, 0, 0, 0);
        }
    }

    @Override
    public void onSaveInstanceState(Bundle savedInstanceState) {
        super.onSaveInstanceState(savedInstanceState);

        // Save data
        savedInstanceState.putSerializable("feedItem", feedItem);
        savedInstanceState.putSerializable("comments", commentAdapter.getComments());
    }
}

package com.android.quickstep.views;

import static android.app.ActivityTaskManager.INVALID_TASK_ID;

import static com.android.launcher3.Flags.enableOverviewIconMenu;
import static com.android.launcher3.util.SplitConfigurationOptions.STAGE_POSITION_BOTTOM_OR_RIGHT;
import static com.android.quickstep.util.SplitScreenUtils.convertLauncherSplitBoundsToShell;

import android.app.ActivityTaskManager;
import android.content.Context;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Pair;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewStub;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.jank.Cuj;
import com.android.launcher3.DeviceProfile;
import com.android.launcher3.R;
import com.android.launcher3.Utilities;
import com.android.launcher3.config.FeatureFlags;
import com.android.launcher3.util.CancellableTask;
import com.android.launcher3.util.RunnableList;
import com.android.launcher3.util.SplitConfigurationOptions;
import com.android.launcher3.util.SplitConfigurationOptions.SplitBounds;
import com.android.launcher3.util.TransformingTouchDelegate;
import com.android.launcher3.views.BaseDragLayer;
import com.android.quickstep.RecentsModel;
import com.android.quickstep.TaskIconCache;
import com.android.quickstep.TaskThumbnailCache;
import com.android.quickstep.util.RecentsOrientedState;
import com.android.quickstep.util.SplitSelectStateController;
import com.android.systemui.shared.recents.model.Task;
import com.android.systemui.shared.recents.model.ThumbnailData;
import com.android.systemui.shared.recents.utilities.PreviewPositionHelper;
import com.android.systemui.shared.system.InteractionJankMonitorWrapper;
import com.android.wm.shell.common.split.SplitScreenConstants.PersistentSnapPosition;

import kotlin.Unit;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * TaskView that contains and shows thumbnails for not one, BUT TWO(!!) tasks
 *
 * That's right. If you call within the next 5 minutes we'll go ahead and double your order and
 * send you !! TWO !! Tasks along with their TaskThumbnailViews complimentary. On. The. House.
 * And not only that, we'll even clean up your thumbnail request if you don't like it.
 * All the benefits of one TaskView, except DOUBLED!
 *
 * (Icon loading sold separately, fees may apply. Shipping & Handling for Overlays not included).
 */
public class GroupedTaskView extends TaskView {

    private static final String TAG = "GroupedTaskView";
    // TODO(b/336612373): Support new TTV for GroupedTaskView
    private TaskThumbnailViewDeprecated mSnapshotView2;
    private TaskViewIcon mIconView2;
    @Nullable
    private CancellableTask<ThumbnailData> mThumbnailLoadRequest2;
    @Nullable
    private CancellableTask mIconLoadRequest2;
    private final float[] mIcon2CenterCoords = new float[2];
    private TransformingTouchDelegate mIcon2TouchDelegate;
    @Nullable
    private SplitBounds mSplitBoundsConfig;
    private final DigitalWellBeingToast mDigitalWellBeingToast2;

    public GroupedTaskView(Context context) {
        this(context, null);
    }

    public GroupedTaskView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public GroupedTaskView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        mDigitalWellBeingToast2 = new DigitalWellBeingToast(mContainer, this);
    }

    /**
     * Returns the second task bound to this TaskView.
     *
     * @deprecated Use {@link #mTaskContainers} instead.
     */
    @Deprecated
    @Nullable
    private Task getSecondTask() {
        return mTaskContainers.size() > 1 ? mTaskContainers.get(1).getTask() : null;
    }

    @Override
    public Unit getThumbnailBounds(@NonNull Rect bounds, boolean relativeToDragLayer) {
        if (mSplitBoundsConfig == null) {
            super.getThumbnailBounds(bounds, relativeToDragLayer);
            return Unit.INSTANCE;
        }
        if (relativeToDragLayer) {
            Rect firstThumbnailBounds = new Rect();
            Rect secondThumbnailBounds = new Rect();
            BaseDragLayer dragLayer = mContainer.getDragLayer();
            dragLayer.getDescendantRectRelativeToSelf(
                    mTaskThumbnailViewDeprecated, firstThumbnailBounds);
            dragLayer.getDescendantRectRelativeToSelf(mSnapshotView2, secondThumbnailBounds);

            bounds.set(firstThumbnailBounds);
            bounds.union(secondThumbnailBounds);
        } else {
            bounds.set(getSnapshotViewBounds(mTaskThumbnailViewDeprecated));
            bounds.union(getSnapshotViewBounds(mSnapshotView2));
        }
        return Unit.INSTANCE;
    }

    private Rect getSnapshotViewBounds(@NonNull View snapshotView) {
        int snapshotViewX = Math.round(snapshotView.getX());
        int snapshotViewY = Math.round(snapshotView.getY());
        return new Rect(snapshotViewX,
                snapshotViewY,
                snapshotViewX + snapshotView.getWidth(),
                snapshotViewY + snapshotView.getHeight());
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mSnapshotView2 = findViewById(R.id.bottomright_snapshot);
        ViewStub iconViewStub2 = findViewById(R.id.bottomRight_icon);
        if (enableOverviewIconMenu()) {
            iconViewStub2.setLayoutResource(R.layout.icon_app_chip_view);
        } else {
            iconViewStub2.setLayoutResource(R.layout.icon_view);
        }
        mIconView2 = (TaskViewIcon) iconViewStub2.inflate();
        mIcon2TouchDelegate = new TransformingTouchDelegate(mIconView2.asView());
    }

    public void bind(Task primary, Task secondary, RecentsOrientedState orientedState,
            @Nullable SplitBounds splitBoundsConfig) {
        super.bind(primary, orientedState);
        mTaskContainers = Arrays.asList(
                mTaskContainers.get(0),
                new TaskContainer(secondary, findViewById(R.id.bottomright_snapshot),
                        mIconView2, STAGE_POSITION_BOTTOM_OR_RIGHT, mDigitalWellBeingToast2));
        mTaskContainers.get(0).setStagePosition(
                SplitConfigurationOptions.STAGE_POSITION_TOP_OR_LEFT);
        mSnapshotView2.bind(secondary);
        mSplitBoundsConfig = splitBoundsConfig;
        if (mSplitBoundsConfig == null) {
            return;
        }
        mTaskThumbnailViewDeprecated.getPreviewPositionHelper().setSplitBounds(
                convertLauncherSplitBoundsToShell(splitBoundsConfig),
                PreviewPositionHelper.STAGE_POSITION_TOP_OR_LEFT);
        mSnapshotView2.getPreviewPositionHelper().setSplitBounds(
                convertLauncherSplitBoundsToShell(splitBoundsConfig),
                PreviewPositionHelper.STAGE_POSITION_BOTTOM_OR_RIGHT);
    }

    /**
     * Sets up an on-click listener and the visibility for show_windows icon on top of each task.
     */
    @Override
    public void setUpShowAllInstancesListener() {
        // sets up the listener for the left/top task
        super.setUpShowAllInstancesListener();
        if (mTaskContainers.size() < 2) {
            return;
        }

        // right/bottom task's base package name
        String taskPackageName = mTaskContainers.get(1).getTask().key.getPackageName();

        // icon of the right/bottom task
        View showWindowsView = findViewById(R.id.show_windows_right);
        updateFilterCallback(showWindowsView, getFilterUpdateCallback(taskPackageName));
    }

    @Override
    public void onTaskListVisibilityChanged(boolean visible, int changes) {
        super.onTaskListVisibilityChanged(visible, changes);
        if (visible) {
            RecentsModel model = RecentsModel.INSTANCE.get(getContext());
            TaskThumbnailCache thumbnailCache = model.getThumbnailCache();
            TaskIconCache iconCache = model.getIconCache();

            if (needsUpdate(changes, FLAG_UPDATE_THUMBNAIL)) {
                mThumbnailLoadRequest2 = thumbnailCache.updateThumbnailInBackground(
                        getSecondTask(),
                        thumbnailData -> mSnapshotView2.setThumbnail(getSecondTask(),
                                thumbnailData
                        ));
            }

            if (needsUpdate(changes, FLAG_UPDATE_ICON)) {
                mIconLoadRequest2 = iconCache.updateIconInBackground(getSecondTask(),
                        (task) -> {
                            setIcon(mIconView2, task.icon);
                            if (enableOverviewIconMenu()) {
                                setText(mIconView2, task.title);
                            }
                            mDigitalWellBeingToast2.initialize(getSecondTask());
                            mDigitalWellBeingToast2.setSplitConfiguration(mSplitBoundsConfig);
                            mDigitalWellBeingToast.setSplitConfiguration(mSplitBoundsConfig);
                        });
            }
        } else {
            if (needsUpdate(changes, FLAG_UPDATE_THUMBNAIL)) {
                mSnapshotView2.setThumbnail(null, null);
                // Reset the task thumbnail reference as well (it will be fetched from the cache or
                // reloaded next time we need it)
                getSecondTask().thumbnail = null;
            }
            if (needsUpdate(changes, FLAG_UPDATE_ICON)) {
                setIcon(mIconView2, null);
                if (enableOverviewIconMenu()) {
                    setText(mIconView2, null);
                }
            }
        }
    }

    public void updateSplitBoundsConfig(SplitBounds splitBounds) {
        mSplitBoundsConfig = splitBounds;
        invalidate();
    }

    @Nullable
    public SplitBounds getSplitBoundsConfig() {
        return mSplitBoundsConfig;
    }

    /**
     * Returns the {@link PersistentSnapPosition} of this pair of tasks.
     */
    public @PersistentSnapPosition int getSnapPosition() {
        if (mSplitBoundsConfig == null) {
            throw new IllegalStateException("mSplitBoundsConfig is null");
        }

        return mSplitBoundsConfig.snapPosition;
    }

    @Override
    public boolean offerTouchToChildren(MotionEvent event) {
        computeAndSetIconTouchDelegate(mIconView2, mIcon2CenterCoords, mIcon2TouchDelegate);
        if (mIcon2TouchDelegate.onTouchEvent(event)) {
            return true;
        }

        return super.offerTouchToChildren(event);
    }

    @Override
    protected void cancelPendingLoadTasks() {
        super.cancelPendingLoadTasks();
        if (mThumbnailLoadRequest2 != null) {
            mThumbnailLoadRequest2.cancel();
            mThumbnailLoadRequest2 = null;
        }
        if (mIconLoadRequest2 != null) {
            mIconLoadRequest2.cancel();
            mIconLoadRequest2 = null;
        }
    }

    @Nullable
    @Override
    public RunnableList launchTaskAnimated() {
        if (mTaskContainers.isEmpty()) {
            return null;
        }

        RunnableList endCallback = new RunnableList();
        RecentsView recentsView = getRecentsView();
        // Callbacks run from remote animation when recents animation not currently running
        InteractionJankMonitorWrapper.begin(this, Cuj.CUJ_SPLIT_SCREEN_ENTER,
                "Enter form GroupedTaskView");
        launchTaskInternal(success -> {
            endCallback.executeAllAndDestroy();
            InteractionJankMonitorWrapper.end(Cuj.CUJ_SPLIT_SCREEN_ENTER);
        }, false /* freezeTaskList */, true /*launchingExistingTaskview*/);

        // Callbacks get run from recentsView for case when recents animation already running
        recentsView.addSideTaskLaunchCallback(endCallback);
        return endCallback;
    }

    @Override
    public void launchTask(@NonNull Consumer<Boolean> callback, boolean isQuickswitch) {
        launchTaskInternal(callback, isQuickswitch, false /*launchingExistingTaskview*/);
    }

    /**
     * @param launchingExistingTaskView {@link SplitSelectStateController#launchExistingSplitPair}
     * uses existence of GroupedTaskView as control flow of how to animate in the incoming task. If
     * we're launching from overview (from overview thumbnails) then pass in {@code true},
     * otherwise pass in {@code false} for case like quickswitching from home to task
     */
    private void launchTaskInternal(@NonNull Consumer<Boolean> callback, boolean isQuickswitch,
            boolean launchingExistingTaskView) {
        getRecentsView().getSplitSelectController().launchExistingSplitPair(
                launchingExistingTaskView ? this : null, getFirstTask().key.id,
                getSecondTask().key.id, SplitConfigurationOptions.STAGE_POSITION_TOP_OR_LEFT,
                callback, isQuickswitch, getSnapPosition());
        Log.d(TAG, "launchTaskInternal - launchExistingSplitPair: " + Arrays.toString(
                getTaskIds()));
    }

    @Override
    void refreshThumbnails(@Nullable HashMap<Integer, ThumbnailData> thumbnailDatas) {
        super.refreshThumbnails(thumbnailDatas);
        if (getSecondTask() != null && thumbnailDatas != null) {
            final ThumbnailData thumbnailData = thumbnailDatas.get(getSecondTask().key.id);
            if (thumbnailData != null) {
                mSnapshotView2.setThumbnail(getSecondTask(), thumbnailData);
                return;
            }
        }

        mSnapshotView2.refresh();
    }

    /**
     * Returns taskId that split selection was initiated with,
     * {@link ActivityTaskManager#INVALID_TASK_ID} if no tasks in this TaskView are part of
     * split selection
     */
    protected int getThisTaskCurrentlyInSplitSelection() {
        int initialTaskId = getRecentsView().getSplitSelectController().getInitialTaskId();
        return containsTaskId(initialTaskId) ? initialTaskId : INVALID_TASK_ID;
    }

    @Override
    protected int getLastSelectedChildTaskIndex() {
        SplitSelectStateController splitSelectController =
                getRecentsView().getSplitSelectController();
        if (splitSelectController.isDismissingFromSplitPair()) {
            // return the container index of the task that wasn't initially selected to split with
            // because that is the only remaining app that can be selected. The coordinate checks
            // below aren't reliable since both of those views may be gone/transformed
            int initSplitTaskId = getThisTaskCurrentlyInSplitSelection();
            if (initSplitTaskId != INVALID_TASK_ID) {
                return initSplitTaskId == getFirstTask().key.id ? 1 : 0;
            }
        }

        // Check which of the two apps was selected
        if (isCoordInView(mIconView2.asView(), mLastTouchDownPosition)
                || isCoordInView(mSnapshotView2, mLastTouchDownPosition)) {
            return 1;
        }
        return super.getLastSelectedChildTaskIndex();
    }

    private boolean isCoordInView(View v, PointF position) {
        float[] localPos = new float[]{position.x, position.y};
        Utilities.mapCoordInSelfToDescendant(v, this, localPos);
        return Utilities.pointInView(v, localPos[0], localPos[1], 0f /* slop */);
    }

    @Override
    public void onRecycle() {
        super.onRecycle();
        mSnapshotView2.setThumbnail(getSecondTask(), null);
        mSplitBoundsConfig = null;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        int widthSize = MeasureSpec.getSize(widthMeasureSpec);
        int heightSize = MeasureSpec.getSize(heightMeasureSpec);
        setMeasuredDimension(widthSize, heightSize);
        if (mSplitBoundsConfig == null || mTaskThumbnailViewDeprecated == null
                || mSnapshotView2 == null) {
            return;
        }
        int initSplitTaskId = getThisTaskCurrentlyInSplitSelection();
        if (initSplitTaskId == INVALID_TASK_ID) {
            getPagedOrientationHandler().measureGroupedTaskViewThumbnailBounds(
                    mTaskThumbnailViewDeprecated,
                    mSnapshotView2, widthSize, heightSize, mSplitBoundsConfig,
                    mContainer.getDeviceProfile(), getLayoutDirection() == LAYOUT_DIRECTION_RTL);
            // Should we be having a separate translation step apart from the measuring above?
            // The following only applies to large screen for now, but for future reference
            // we'd want to abstract this out in PagedViewHandlers to get the primary/secondary
            // translation directions
            mTaskThumbnailViewDeprecated.applySplitSelectTranslateX(
                    mTaskThumbnailViewDeprecated.getTranslationX());
            mTaskThumbnailViewDeprecated.applySplitSelectTranslateY(
                    mTaskThumbnailViewDeprecated.getTranslationY());
            mSnapshotView2.applySplitSelectTranslateX(mSnapshotView2.getTranslationX());
            mSnapshotView2.applySplitSelectTranslateY(mSnapshotView2.getTranslationY());
        } else {
            // Currently being split with this taskView, let the non-split selected thumbnail
            // take up full thumbnail area
            Optional<TaskContainer> nonSplitContainer = mTaskContainers.stream().filter(
                    container -> container.getTask().key.id != initSplitTaskId).findAny();
            nonSplitContainer.ifPresent(
                    taskIdAttributeContainer -> taskIdAttributeContainer.getThumbnailView().measure(
                            widthMeasureSpec, MeasureSpec.makeMeasureSpec(
                                    heightSize - mContainer.getDeviceProfile()
                                            .overviewTaskThumbnailTopMarginPx,
                                    MeasureSpec.EXACTLY)));
        }
        if (!enableOverviewIconMenu()) {
            updateIconPlacement();
        }
    }

    @Override
    public void setOverlayEnabled(boolean overlayEnabled) {
        if (FeatureFlags.enableAppPairs()) {
            super.setOverlayEnabled(overlayEnabled);
        } else {
            // Intentional no-op to prevent setting smart actions overlay on thumbnails
        }
    }

    @Override
    public void setOrientationState(RecentsOrientedState orientationState) {
        DeviceProfile deviceProfile = mContainer.getDeviceProfile();
        if (enableOverviewIconMenu() && mSplitBoundsConfig != null) {
            ViewGroup.LayoutParams layoutParams = getLayoutParams();
            Pair<Point, Point> groupedTaskViewSizes =
                    orientationState.getOrientationHandler().getGroupedTaskViewSizes(
                            deviceProfile,
                            mSplitBoundsConfig,
                            layoutParams.width,
                            layoutParams.height
                    );
            int iconViewMarginStart = getResources().getDimensionPixelSize(
                    R.dimen.task_thumbnail_icon_menu_expanded_top_start_margin);
            int iconViewBackgroundMarginStart = getResources().getDimensionPixelSize(
                    R.dimen.task_thumbnail_icon_menu_background_margin_top_start);
            int iconMargins = (iconViewMarginStart + iconViewBackgroundMarginStart) * 2;
            ((IconAppChipView) mIconView).setMaxWidth(groupedTaskViewSizes.first.x - iconMargins);
            ((IconAppChipView) mIconView2).setMaxWidth(groupedTaskViewSizes.second.x - iconMargins);
        }
        // setMaxWidth() needs to be called before mIconView.setIconOrientation which is called in
        // the super below.
        super.setOrientationState(orientationState);

        boolean isGridTask = deviceProfile.isTablet && !isFocusedTask();
        mIconView2.setIconOrientation(orientationState, isGridTask);
        updateIconPlacement();
        updateSecondaryDwbPlacement();
    }

    private void updateIconPlacement() {
        if (mSplitBoundsConfig == null) {
            return;
        }

        DeviceProfile deviceProfile = mContainer.getDeviceProfile();
        int taskIconHeight = deviceProfile.overviewTaskIconSizePx;
        boolean isRtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;

        if (enableOverviewIconMenu()) {
            ViewGroup.LayoutParams layoutParams = getLayoutParams();
            Pair<Point, Point> groupedTaskViewSizes =
                    getPagedOrientationHandler()
                            .getGroupedTaskViewSizes(
                                    deviceProfile,
                                    mSplitBoundsConfig,
                                    layoutParams.width,
                                    layoutParams.height
                            );

            getPagedOrientationHandler().setSplitIconParams(mIconView.asView(), mIconView2.asView(),
                    taskIconHeight, groupedTaskViewSizes.first.x, groupedTaskViewSizes.first.y,
                    getLayoutParams().height, getLayoutParams().width, isRtl, deviceProfile,
                    mSplitBoundsConfig);
        } else {
            getPagedOrientationHandler().setSplitIconParams(mIconView.asView(), mIconView2.asView(),
                    taskIconHeight, mTaskThumbnailViewDeprecated.getMeasuredWidth(),
                    mTaskThumbnailViewDeprecated.getMeasuredHeight(), getMeasuredHeight(),
                    getMeasuredWidth(),
                    isRtl, deviceProfile, mSplitBoundsConfig);
        }
    }

    private void updateSecondaryDwbPlacement() {
        if (getSecondTask() == null) {
            return;
        }
        mDigitalWellBeingToast2.initialize(getSecondTask());
    }

    @Override
    protected void updateSnapshotRadius() {
        super.updateSnapshotRadius();
        mSnapshotView2.setFullscreenParams(mCurrentFullscreenParams);
    }

    @Override
    protected void setIconsAndBannersTransitionProgress(float progress, boolean invert) {
        super.setIconsAndBannersTransitionProgress(progress, invert);
        // Value set by super call
        float scale = mIconView.getAlpha();
        mIconView2.setContentAlpha(scale);
        mDigitalWellBeingToast2.updateBannerOffset(1f - scale);
    }

    @Override
    public void setColorTint(float amount, int tintColor) {
        super.setColorTint(amount, tintColor);
        mIconView2.setIconColorTint(tintColor, amount);
        mSnapshotView2.setDimAlpha(amount);
        mDigitalWellBeingToast2.setBannerColorTint(tintColor, amount);
    }

    @Override
    protected void applyThumbnailSplashAlpha() {
        super.applyThumbnailSplashAlpha();
        mSnapshotView2.setSplashAlpha(mTaskThumbnailSplashAlpha);
    }

    @Override
    protected void refreshTaskThumbnailSplash() {
        super.refreshTaskThumbnailSplash();
        mSnapshotView2.refreshSplashView();
    }

    @Override
    protected void resetViewTransforms() {
        super.resetViewTransforms();
        mSnapshotView2.resetViewTransforms();
    }

    /**
     * Sets visibility for thumbnails and associated elements (DWB banners).
     * IconView is unaffected.
     *
     * When setting INVISIBLE, sets the visibility for the last selected child task.
     * When setting VISIBLE (as a reset), sets the visibility for both tasks.
     */
    @Override
    void setThumbnailVisibility(int visibility, int taskId) {
        for (TaskContainer container : mTaskContainers) {
            if (visibility == VISIBLE || container.getTask().key.id == taskId) {
                container.getThumbnailView().setVisibility(visibility);
                container.getDigitalWellBeingToast().setBannerVisibility(visibility);
            }
        }
    }
}

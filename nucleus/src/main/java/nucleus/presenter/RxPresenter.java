package nucleus.presenter;

import android.os.Bundle;

import java.util.ArrayList;
import java.util.HashMap;

import rx.Observable;
import rx.Subscriber;
import rx.Subscription;
import rx.functions.Action0;
import rx.functions.Func0;
import rx.subjects.BehaviorSubject;
import rx.subscriptions.Subscriptions;

/**
 * This is an extension of {@link nucleus.presenter.Presenter} which provides RxJava functionality.
 *
 * @param <ViewType> a type of view
 */
public class RxPresenter<ViewType> extends Presenter<ViewType> {

    private static final String REQUESTED_KEY = RxPresenter.class.getName() + "#requested";

    private ArrayList<Integer> requested = new ArrayList<>();
    private HashMap<Integer, Func0<Subscription>> factories = new HashMap<>();
    private HashMap<Integer, Subscription> subscriptions = new HashMap<>();

    private BehaviorSubject<Boolean> viewStatusSubject = BehaviorSubject.create();

    /**
     * Returns an observable that emits current status of a view.
     * True - a view is attached, False - a view is detached.
     *
     * @return an observable that emits current status of a view.
     */
    public Observable<Boolean> viewStatus() {
        return viewStatusSubject;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void onCreate(Bundle savedState) {
        if (savedState != null)
            requested = savedState.getIntegerArrayList(REQUESTED_KEY);
        viewStatusSubject.onNext(false);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void onDestroy() {
        super.onDestroy();
        for (Subscription subs : subscriptions.values())
            subs.unsubscribe();
        viewStatusSubject.onCompleted();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void onSave(Bundle state) {
        super.onSave(state);
        for (int i = requested.size() - 1; i >= 0; i--) {
            Integer restartableId = requested.get(i);
            if (subscriptions.get(restartableId).isUnsubscribed()) {
                requested.remove(i);
                subscriptions.remove(restartableId);
            }
        }
        state.putIntegerArrayList(REQUESTED_KEY, requested);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void onTakeView(ViewType view) {
        super.onTakeView(view);
        viewStatusSubject.onNext(true);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void onDropView() {
        super.onDropView();
        viewStatusSubject.onNext(false);
    }

    /**
     * A restartable is any RxJava query/request that can be started (subscribed) and
     * should be automatically restarted (re-subscribed) after a process restart.
     * <p/>
     * Registers a factory for a restartable. Re-subscribes the restartable after a process restart.
     *
     * @param restartableId id of a restartable.
     * @param factory       a factory that will create an actual rxjava subscription when requested.
     */
    public void registerRestartable(int restartableId, Func0<Subscription> factory) {
        factories.put(restartableId, factory);
        if (requested.contains(restartableId))
            subscriptions.put(restartableId, factories.get(restartableId).call());
    }

    /**
     * Subscribes (runs) a restartable using a factory method provided with {@link #registerRestartable}.
     * If a presenter gets lost during a process restart while a restartable is still
     * subscribed, the restartable will be restarted on next {@link #registerRestartable} call.
     * <p/>
     * If the restartable is already subscribed then it will be unsubscribed first.
     * <p/>
     * The restartable will be unsubscribed during {@link #onDestroy()}
     *
     * @param restartableId id of a restartable.
     */
    public void subscribeRestartable(int restartableId) {
        unsubscribeRestartable(restartableId);
        requested.add(restartableId);
        subscriptions.put(restartableId, factories.get(restartableId).call());
    }

    /**
     * Unsubscribes a restartable
     *
     * @param restartableId id of a restartable.
     */
    public void unsubscribeRestartable(int restartableId) {
        if (subscriptions.containsKey(restartableId)) {
            subscriptions.get(restartableId).unsubscribe();
            subscriptions.remove(restartableId);
        }
        requested.remove((Integer)restartableId);
    }

    private ArrayList<Object> references = new ArrayList<>();

    /**
     * Returns a transformer that will delay onNext, onError and onComplete emissions unless a view become available.
     * getView() is guaranteed to be != null during all emissions. This transformer can only be used on application's main thread.
     * <p/>
     * Use this operator if you need to deliver *all* emissions to a view, in example when you're sending items
     * into adapter one by one.
     *
     * @param <T> a type of onNext value.
     * @return the delaying operator.
     */
    public <T> Observable.Transformer<T, T> deliver() {
        return transform(new Func0<OperatorSemaphore<T>>() {
            @Override
            public OperatorSemaphore<T> call() {
                return OperatorSemaphore.semaphore(viewStatus());
            }
        });
    }

    /**
     * Returns a transformer that will delay onNext, onError and onComplete emissions unless a view become available.
     * getView() is guaranteed to be != null during all emissions. This transformer can only be used on application's main thread.
     * <p/>
     * If this transformer receives a next value while the previous value has not been delivered, the
     * previous value will be dropped.
     * <p/>
     * Use this operator when you need to show updatable data.
     *
     * @param <T> a type of onNext value.
     * @return the delaying operator.
     */
    public <T> Observable.Transformer<T, T> deliverLatest() {
        return transform(new Func0<OperatorSemaphore<T>>() {
            @Override
            public OperatorSemaphore<T> call() {
                return OperatorSemaphore.semaphoreLatest(viewStatus());
            }
        });
    }

    /**
     * Returns a transformer that will delay onNext, onError and onComplete emissions unless a view become available.
     * getView() is guaranteed to be != null during all emissions. This transformer can only be used on application's main thread.
     * <p/>
     * If the transformer receives a next value while the previous value has not been delivered, the
     * previous value will be dropped.
     * <p/>
     * The transformer will duplicate the latest onNext emission in case if a view has been reattached.
     * <p/>
     * This operator ignores onComplete emission and never sends one.
     * <p/>
     * Use this operator when you need to show updatable data that needs to be cached in memory.
     *
     * @param <T> a type of onNext value.
     * @return the delaying operator.
     */
    public <T> Observable.Transformer<T, T> deliverLatestCache() {
        return transform(new Func0<OperatorSemaphore<T>>() {
            @Override
            public OperatorSemaphore<T> call() {
                return OperatorSemaphore.semaphoreLatestCache(viewStatus());
            }
        });
    }

    private <T> Observable.Transformer<T, T> transform(final Func0<OperatorSemaphore<T>> factory) {
        return new Observable.Transformer<T, T>() {
            @Override
            public Observable<T> call(Observable<T> observable) {
                return observable.lift(new Observable.Operator<T, T>() {
                    @Override
                    public Subscriber<? super T> call(Subscriber<? super T> subscriber) {
                        final OperatorSemaphore<T> semaphore = factory.call();
                        references.add(semaphore);
                        subscriber.add(Subscriptions.create(new Action0() {
                            @Override
                            public void call() {
                                references.remove(semaphore);
                            }
                        }));
                        return semaphore.call(subscriber);
                    }
                });
            }
        };
    }

    /**
     * @hide testing facility
     */
    public boolean viewStatusHasObservers() {
        return viewStatusSubject.hasObservers();
    }
}

package com.elfmcys.ysm.natives.legacy;

import com.elfmcys.ysm.util.CleanerUtil;

import java.lang.ref.Cleaner;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongConsumer;

final class NativeLegacyOwnership implements AutoCloseable {
    private final State state;
    private final Cleaner.Cleanable cleanable;

    NativeLegacyOwnership(long handle, LongConsumer releaser) {
        if (handle == 0) {
            throw new IllegalArgumentException("Native legacy owner handle is zero");
        }
        state = new State(handle, Objects.requireNonNull(releaser, "releaser"));
        cleanable = CleanerUtil.ref(this, state, State::closePublic);
    }

    void requirePublicOpen() {
        state.requirePublicOpen();
    }

    Lease acquire() {
        return state.acquire(true);
    }

    boolean isOpen() {
        return state.publicOpen.get();
    }

    @Override
    public void close() {
        cleanable.clean();
    }

    void cleanForTesting() {
        cleanable.clean();
    }

    static final class Lease implements AutoCloseable {
        private final State owner;
        private final LeaseState state;
        private final Cleaner.Cleanable cleanable;

        private Lease(State owner) {
            this.owner = owner;
            state = new LeaseState(owner);
            cleanable = CleanerUtil.ref(this, state, LeaseState::close);
        }

        private static Lease retained(State owner, boolean requirePublic) {
            owner.retain(requirePublic);
            try {
                return new Lease(owner);
            } catch (Throwable failure) {
                owner.release();
                throw failure;
            }
        }

        void requireOpen() {
            if (!state.open.get()) {
                throw new IllegalStateException("Legacy payload lease is closed");
            }
        }

        Lease acquire() {
            requireOpen();
            return retained(owner, false);
        }

        @Override
        public void close() {
            cleanable.clean();
        }
    }

    private static final class LeaseState {
        private final State owner;
        private final AtomicBoolean open = new AtomicBoolean(true);

        private LeaseState(State owner) {
            this.owner = owner;
        }

        private void close() {
            if (open.compareAndSet(true, false)) {
                owner.release();
            }
        }
    }

    private static final class State {
        private final long handle;
        private final LongConsumer releaser;
        private final AtomicBoolean publicOpen = new AtomicBoolean(true);
        private final AtomicInteger references = new AtomicInteger(1);
        private final AtomicBoolean released = new AtomicBoolean();

        private State(long handle, LongConsumer releaser) {
            this.handle = handle;
            this.releaser = releaser;
        }

        private void requirePublicOpen() {
            if (!publicOpen.get()) {
                throw new IllegalStateException("Native legacy result is closed");
            }
        }

        private Lease acquire(boolean requirePublic) {
            return Lease.retained(this, requirePublic);
        }

        private void retain(boolean requirePublic) {
            while (true) {
                if (requirePublic && !publicOpen.get()) {
                    throw new IllegalStateException("Native legacy result is closed");
                }
                int current = references.get();
                if (current == 0) {
                    throw new IllegalStateException("Native legacy result is released");
                }
                if (current == Integer.MAX_VALUE) {
                    throw new IllegalStateException("Too many legacy payload owners");
                }
                if (requirePublic && !publicOpen.get()) {
                    continue;
                }
                if (references.compareAndSet(current, current + 1)) {
                    return;
                }
            }
        }

        private void closePublic() {
            if (publicOpen.compareAndSet(true, false)) {
                release();
            }
        }

        private void release() {
            int remaining = references.decrementAndGet();
            if (remaining < 0) {
                throw new IllegalStateException("Legacy owner reference underflow");
            }
            if (remaining == 0 && released.compareAndSet(false, true)) {
                releaser.accept(handle);
            }
        }
    }
}

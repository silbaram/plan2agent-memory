import ai.djl.ndarray.NDManager;

public final class DjlNativePrefetch {
    private DjlNativePrefetch() {
    }

    public static void main(String[] arguments) {
        try (NDManager manager = NDManager.newBaseManager("PyTorch")) {
            manager.create(new float[] {0.0f}).getShape();
        }
    }
}

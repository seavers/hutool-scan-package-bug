package demo.permissions;

public enum AiPermissionKey implements PermissionKey {
    GENERATE_TITLE("ai#generate_title#generate#web");

    private final String key;

    AiPermissionKey(String key) {
        this.key = key;
    }

    @Override
    public String getKey() {
        return key;
    }
}

// IBoundaryApi.aidl
// This is a copy of Boundary's published API contract. It MUST stay byte-for-byte
// compatible with the one inside Boundary (same package, same method order/signatures).
package app.boundary.blocker.api;

interface IBoundaryApi {
    int apiVersion();
    boolean isGranted();
    String execute(String command, in Bundle args);
    String query(String state);
    String list(String kind);
}

import SwiftUI

/// Full-screen, pinch-to-zoom viewer for a product photo. Reused everywhere a
/// thumbnail can be tapped to enlarge (item form, Items list, Shop-at-Store
/// rows). Present it in a `.fullScreenCover`; callers should only do so when
/// they have a non-nil image URL or a staged local image.
///
///  - Pinch to zoom (clamped 1x–5x), drag to pan while zoomed.
///  - Double-tap toggles between fit (1x) and 2.5x.
///  - Tap the backdrop, tap the close button, or swipe down to dismiss.
struct ZoomableImageView: View {
    /// A photo can come from Storage (saved item) or straight from the
    /// picker/camera (staged in the form, not yet uploaded). Same gestures
    /// either way.
    enum Source {
        case remote(String)
        case local(UIImage)
    }

    let source: Source
    @Environment(\.dismiss) private var dismiss

    init(imageUrl: String) {
        self.source = .remote(imageUrl)
    }

    init(image: UIImage) {
        self.source = .local(image)
    }

    @State private var scale: CGFloat = 1
    @State private var lastScale: CGFloat = 1
    @State private var offset: CGSize = .zero
    @State private var lastOffset: CGSize = .zero

    var body: some View {
        ZStack {
            Color.black.opacity(0.92).ignoresSafeArea()
                .onTapGesture { dismiss() }

            imageContent
                .scaleEffect(scale)
                .offset(offset)
                .gesture(magnification)
                .simultaneousGesture(panDrag)
                .highPriorityGesture(dismissDrag)
                .onTapGesture(count: 2, perform: toggleZoom)

            VStack {
                HStack {
                    Spacer()
                    Button {
                        dismiss()
                    } label: {
                        Image(systemName: "xmark")
                            .font(.title3.weight(.semibold))
                            .foregroundStyle(.white)
                            .padding(10)
                    }
                    .accessibilityLabel(Text(L("image_viewer_close")))
                }
                Spacer()
            }
            .padding(8)
        }
    }

    @ViewBuilder
    private var imageContent: some View {
        switch source {
        case .local(let uiImage):
            Image(uiImage: uiImage)
                .resizable()
                .scaledToFit()
                .accessibilityLabel(Text(L("image_viewer_cd")))
        case .remote(let imageUrl):
            if let url = URL(string: imageUrl) {
                AsyncImage(url: url) { phase in
                    switch phase {
                    case .success(let image):
                        image.resizable().scaledToFit()
                    case .empty:
                        ProgressView().tint(.white)
                    case .failure:
                        Image(systemName: "photo")
                            .font(.largeTitle)
                            .foregroundStyle(.white.opacity(0.6))
                    @unknown default:
                        EmptyView()
                    }
                }
                .accessibilityLabel(Text(L("image_viewer_cd")))
            }
        }
    }

    private var magnification: some Gesture {
        MagnificationGesture()
            .onChanged { value in
                scale = min(max(lastScale * value, 1), 5)
            }
            .onEnded { _ in
                lastScale = scale
                if scale <= 1 { resetPan() }
            }
    }

    private var panDrag: some Gesture {
        DragGesture()
            .onChanged { value in
                // Only pan while zoomed in; at 1x the dismiss-drag owns the
                // vertical swipe.
                guard scale > 1 else { return }
                offset = CGSize(
                    width: lastOffset.width + value.translation.width,
                    height: lastOffset.height + value.translation.height
                )
            }
            .onEnded { _ in lastOffset = offset }
    }

    /// When not zoomed, a downward drag dismisses the viewer (standard
    /// photo-viewer gesture). Only active at 1x so it doesn't fight panning.
    private var dismissDrag: some Gesture {
        DragGesture(minimumDistance: 20)
            .onEnded { value in
                if scale <= 1, value.translation.height > 80 {
                    dismiss()
                }
            }
    }

    private func toggleZoom() {
        withAnimation(.easeInOut(duration: 0.2)) {
            if scale > 1 {
                scale = 1; lastScale = 1; resetPan()
            } else {
                scale = 2.5; lastScale = 2.5
            }
        }
    }

    private func resetPan() {
        offset = .zero
        lastOffset = .zero
    }
}

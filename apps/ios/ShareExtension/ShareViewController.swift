#if canImport(UIKit)
import UIKit
import UniformTypeIdentifiers
import AmemeShared

/// The real Share Extension entry point. It only writes a bounded, opaque handoff
/// into the App Group; the containing app remains responsible for user confirmation
/// and the final local Event commit.
final class ShareViewController: UIViewController {
    private let statusLabel = UILabel()
    private let activityIndicator = UIActivityIndicatorView(style: .medium)
    private let retryButton = UIButton(type: .system)
    private var didStart = false
    private var didFinish = false

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemBackground
        statusLabel.numberOfLines = 0
        statusLabel.textAlignment = .center
        statusLabel.textColor = .label
        statusLabel.accessibilityTraits = .staticText

        retryButton.setTitle("重试", for: .normal)
        retryButton.addTarget(self, action: #selector(retry), for: .touchUpInside)
        retryButton.isHidden = true

        let cancelButton = UIButton(type: .system)
        cancelButton.setTitle("取消", for: .normal)
        cancelButton.addTarget(self, action: #selector(cancel), for: .touchUpInside)

        let stack = UIStackView(arrangedSubviews: [statusLabel, activityIndicator, retryButton, cancelButton])
        stack.axis = .vertical
        stack.alignment = .center
        stack.spacing = 16
        stack.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(stack)
        NSLayoutConstraint.activate([
            stack.leadingAnchor.constraint(greaterThanOrEqualTo: view.layoutMarginsGuide.leadingAnchor),
            stack.trailingAnchor.constraint(lessThanOrEqualTo: view.layoutMarginsGuide.trailingAnchor),
            stack.centerYAnchor.constraint(equalTo: view.centerYAnchor),
            statusLabel.widthAnchor.constraint(lessThanOrEqualToConstant: 300),
        ])
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        if !didStart { start() }
    }

    @objc private func retry() {
        guard !didFinish else { return }
        didStart = false
        start()
    }

    @objc private func cancel() {
        finish()
    }

    private func start() {
        guard !didFinish, !didStart else { return }
        didStart = true
        retryButton.isHidden = true
        activityIndicator.startAnimating()
        statusLabel.text = "正在准备分享内容…"
        guard let groupRoot = IncomingShareHandoffStore.appGroupRoot() else {
            fail("分享扩展未获得 App Group 权限；没有保存内容。")
            return
        }
        guard let inputItem = extensionContext?.inputItems.first as? NSExtensionItem,
              let attachments = inputItem.attachments,
              attachments.count == 1,
              let provider = attachments.first else {
            fail("一次只能分享一项内容；没有保存内容。")
            return
        }

        let store = IncomingShareHandoffStore(rootDirectory: groupRoot)
        if provider.hasItemConformingToTypeIdentifier(UTType.pdf.identifier) {
            loadFile(provider, type: .pdf, kind: .pdf, store: store)
        } else if provider.hasItemConformingToTypeIdentifier(UTType.image.identifier) {
            loadFile(provider, type: .image, kind: .image, store: store)
        } else if provider.hasItemConformingToTypeIdentifier(UTType.plainText.identifier) {
            loadText(provider, typeIdentifier: UTType.plainText.identifier, store: store)
        } else if provider.hasItemConformingToTypeIdentifier(UTType.url.identifier) {
            loadText(provider, typeIdentifier: UTType.url.identifier, store: store)
        } else {
            fail("暂不支持此分享类型；没有保存内容。")
        }
    }

    private func loadText(
        _ provider: NSItemProvider,
        typeIdentifier: String,
        store: IncomingShareHandoffStore
    ) {
        provider.loadItem(forTypeIdentifier: typeIdentifier, options: nil) { [weak self] item, error in
            guard let self else { return }
            if let error {
                self.fail("读取分享文字失败：\(error.localizedDescription)")
                return
            }
            let text: String?
            switch item {
            case let value as String:
                text = value
            case let value as URL:
                text = value.absoluteString
            case let value as NSURL:
                text = value.absoluteString
            case let value as Data:
                text = String(data: value, encoding: .utf8)
            default:
                text = nil
            }
            guard let text else {
                self.fail("分享内容不是可读取的文字；没有保存内容。")
                return
            }
            do {
                let handoffURL = try store.writeText(text)
                self.openApp(handoffURL)
            } catch {
                self.fail(self.userMessage(for: error))
            }
        }
    }

    private func loadFile(
        _ provider: NSItemProvider,
        type: UTType,
        kind: IncomingShareKind,
        store: IncomingShareHandoffStore
    ) {
        provider.loadFileRepresentation(forTypeIdentifier: type.identifier) { [weak self] temporaryURL, error in
            guard let self else { return }
            if let error {
                self.fail("读取分享文件失败：\(error.localizedDescription)")
                return
            }
            guard let temporaryURL,
                  let data = try? Data(contentsOf: temporaryURL) else {
                self.fail("分享文件不可用；没有保存内容。")
                return
            }
            do {
                let handoffURL = try store.writeFile(
                    data,
                    kind: kind,
                    mimeType: kind.mimeType,
                    displayName: temporaryURL.lastPathComponent
                )
                self.openApp(handoffURL)
            } catch {
                self.fail(self.userMessage(for: error))
            }
        }
    }

    private func openApp(_ url: URL) {
        DispatchQueue.main.async { [weak self] in
            guard let self, !self.didFinish else { return }
            self.activityIndicator.stopAnimating()
            self.statusLabel.text = "已准备分享内容，正在交给 Ameme 确认…"
            self.extensionContext?.open(url) { [weak self] opened in
                guard let self else { return }
                if opened {
                    self.finish()
                } else {
                    self.fail("无法打开 Ameme 确认页；分享内容仍未保存。")
                }
            }
        }
    }

    private func fail(_ message: String) {
        DispatchQueue.main.async { [weak self] in
            guard let self, !self.didFinish else { return }
            self.activityIndicator.stopAnimating()
            self.statusLabel.text = message
            self.statusLabel.accessibilityLabel = message
            self.retryButton.isHidden = false
            self.didStart = false
        }
    }

    private func finish() {
        guard !didFinish else { return }
        didFinish = true
        extensionContext?.completeRequest(returningItems: nil)
    }

    private func userMessage(for error: Error) -> String {
        switch error as? IncomingSharePayloadError {
        case .fileTooLarge:
            return "分享文件超过 32 MB；没有保存内容。"
        case .missingText:
            return "分享文字为空；没有保存内容。"
        case .textTooLong:
            return "分享文字过长；没有保存内容。"
        case .invalidMimeType, .invalidSchema, .missingFile:
            return "分享内容不符合安全边界；没有保存内容。"
        default:
            return "分享内容暂时无法保存；没有保存内容。"
        }
    }
}
#endif

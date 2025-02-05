import Flutter
import UIKit
import ContactsUI

@available(iOS 9.0, *)
public class SwiftFlutterContactPickerPlugin: NSObject, FlutterPlugin {
    
    private var pickerDelegate: CNContactPickerDelegate?
    
    public static func register(with registrar: FlutterPluginRegistrar) {
        let channel = FlutterMethodChannel(name: "me.schlaubi.contactpicker", binaryMessenger: registrar.messenger())
        let instance = SwiftFlutterContactPickerPlugin()
        registrar.addMethodCallDelegate(instance, channel: channel)
    }
    
    private func requestPicker(result: @escaping FlutterResult, type: String, neededProperty: String?) {
        let controller = CNContactPickerViewController()
        pickerDelegate = ContactPickerDelegate(result: result, type: type)
        controller.delegate = pickerDelegate
        controller.predicateForSelectionOfContact = NSPredicate(format:"phoneNumbers.@count== 1")
        if(neededProperty != nil) {
            controller.displayedPropertyKeys = [neededProperty!]
        } else {
            controller.displayedPropertyKeys = [
                CNContactPhoneNumbersKey,
                CNContactEmailAddressesKey,
                CNContactPostalAddressesKey,
                CNContactInstantMessageAddressesKey
            ]
        }
        // find proper keyWindow
        var keyWindow: UIWindow? = nil
        if #available(iOS 13, *) {
            keyWindow = UIApplication.shared.connectedScenes.filter {
                $0.activationState == .foregroundActive
            }.compactMap { $0 as? UIWindowScene
            }.first?.windows.first(where: { $0.isKeyWindow }) ?? UIApplication.shared.windows.first
        } else {
            keyWindow = UIApplication.shared.keyWindow
        }
        
        // Get the topmost view controller instead of just the root
        if let rootViewController = keyWindow?.rootViewController {
            var topController = rootViewController
            while let presentedViewController = topController.presentedViewController {
                topController = presentedViewController
            }
            topController.present(controller, animated: true, completion: nil)
        }
    }
    
    public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        switch call.method {
        case "pickPhoneContact":
            requestPicker(result: result, type: "phoneNumber", neededProperty: CNContactPhoneNumbersKey)
            break
        case "pickEmailContact":
            requestPicker(result: result, type: "email", neededProperty: CNContactEmailAddressesKey)
            break
//        case "pickContact":
//            requestPicker(result: result, type: "full", neededProperty: nil)
//            break;
        case "hasPermission":
            result(true)
            break;
        case "requestPermission":
            result(true)
            break;
        default:
            result(FlutterMethodNotImplemented)
        }
  }
}

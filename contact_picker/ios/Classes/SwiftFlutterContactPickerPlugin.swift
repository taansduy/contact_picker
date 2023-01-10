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
        var viewController = UIApplication.shared.delegate?.window??.rootViewController
        while ((viewController?.presentedViewController) != nil) {
            viewController = viewController?.presentedViewController
        }
        viewController?.present(controller, animated: true, completion: nil)
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

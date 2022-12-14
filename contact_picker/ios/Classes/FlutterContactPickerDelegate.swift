//
//  FlutterContactPickerDelegate.swift
//  fluttercontactpicker
//
//  Created by Moritz Douda on 05.03.20.
//

import Foundation
import ContactsUI

@available(iOS 9.0, *)
public class ContactPickerDelegate : NSObject, CNContactPickerDelegate {
    
    private let result: FlutterResult
    private let type: String
    
    init(result: @escaping FlutterResult, type: String) {
        self.result = result
        self.type = type
    }
    
    public func contactPicker(_ picker: CNContactPickerViewController, didSelect contactProperty: CNContactProperty) {
        let contact = contactProperty.contact
        let fullName = CNContactFormatter.string(from: contact, style: CNContactFormatterStyle.fullName)
        let itemRaw = contactProperty.value
        if(type == "full") {
            let keys = [
                CNContactMiddleNameKey,
                CNContactFamilyNameKey,
                CNContactGivenNameKey,
                CNContactNicknameKey,
                CNContactPhoneNumbersKey,
                CNContactEmailAddressesKey,
                CNContactPostalAddressesKey,
                CNContactInstantMessageAddressesKey
            ]
            
            let emails = contact.emailAddresses.map({
                (emailAddress) -> Any in
                return [
                    "label": stringifyLabel(label: emailAddress.label),
                    "email": emailAddress.value
                ];
            })
            
            
            let name = [
                "firstName": contact.givenName,
                "middleName": contact.middleName,
                "nickName": contact.nickname,
                "lastName": contact.familyName
            ]
            
            let instantMessengers = contact.instantMessageAddresses.map { (instantMessenger) -> Dictionary<String, Any> in
                return [
                    "customLabel": stringifyLabel(label: instantMessenger.label),
                    "im": instantMessenger.value.username,
                    "protocol": instantMessenger.value.service
                ];
            }
            
            let addresses = contact.postalAddresses.map { (address) -> Dictionary<String, Any> in
                var dict = [
                    "customLabel": stringifyLabel(label: address.label),
                    "street": address.value.street,
                    "city": address.value.city,
                    "postcode": address.value.postalCode,
                    "country": address.value.country
                ]
                
                if #available(iOS 10.3, *) {
                    dict["neighborhood"] = address.value.subLocality
                    dict["postcode"] = address.value.subAdministrativeArea
                }
                
                return dict
            }
            let phones = contact.phoneNumbers.map { (phoneNumber) -> Dictionary<String, Any> in
                return [
                    "phoneNumber": phoneNumber.value.stringValue,
                    "label": stringifyLabel(label: phoneNumber.label)
                ];
            }
            result([
                "instantMessengers": instantMessengers,
                "emails": emails,
                "phones": phones,
                "addresses": addresses
            ])
        } else {
            var item: Any?
            if(itemRaw is CNPhoneNumber) {
                item = (itemRaw as! CNPhoneNumber).stringValue
            } else {
                item = itemRaw
            }
            let label = stringifyLabel(label: contactProperty.label)
            let dict = [
                "fullName": fullName as Any,
                type: [
                    type: item,
                    "label": label
                ],
                ] as [String : Any]
            print(dict)
            result(dict)
        }
    }

    @available(iOS 9.0, *)
     public func contactPicker(_ picker: CNContactPickerViewController, didSelect contact: CNContact) {
        var fullName = CNContactFormatter.string(from: contact, style: CNContactFormatterStyle.fullName)
        let numbers: Array<String> = contact.phoneNumbers.compactMap { $0.value.stringValue as String }
        let dict = [
            "fullName": fullName as Any,
            type: [
                type: numbers.first ?? "",
                "label": stringifyLabel(label: "mobile")
            ],
        ]  as [String : Any]
         result(dict)
    
     }

    private func stringifyLabel(label: String?) -> String {
        if(label == nil) {
            return ""
        }
        
        return CNLabeledValue<NSString>.localizedString(forLabel: label!)
    }
    
    public func contactPickerDidCancel(_ picker: CNContactPickerViewController) {
        result(nil)
    }
}

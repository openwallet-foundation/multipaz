import SwiftUI
import Multipaz

struct KvPair: View {
    let key: String
    let string: String?
    let numBytes: Int32?
    let instant: KotlinInstant?
    let bool: Bool?
    let encodedImage: Data?

    private let dateFormatter: DateFormatter

    init(
        _ key: String,
        string: String? = nil,
        numBytes: Int32? = nil,
        instant: KotlinInstant? = nil,
        bool: Bool? = nil,
        encodedImage: Data? = nil
    ) {
        self.key = key
        self.string = string
        self.numBytes = numBytes
        self.instant = instant
        self.bool = bool
        self.encodedImage = encodedImage

        self.dateFormatter = DateFormatter()
        dateFormatter.dateStyle = .long
        dateFormatter.timeStyle = .long
    }
    
    var body: some View {
        VStack(alignment: .leading) {
            Text(key).bold()
            if string != nil {
                Text(string!)
            } else if numBytes != nil {
                if numBytes! < 0 {
                    Text("Not set")
                } else {
                    Text("\(numBytes!.formatted()) bytes")
                }
            } else if instant != nil {
                Text(dateFormatter.string(from: instant!.toNSDate()))
            } else if bool != nil {
                Text(bool! ? "True" : "False")
            } else if encodedImage != nil {
                if let image = UIImage(data: encodedImage!) {
                    Text("Image of \(encodedImage!.count.formatted()) bytes")
                    Image(uiImage: image)
                        .resizable()
                        .scaledToFit()
                        .frame(height: 200)
                } else {
                    Text("Error decoding image of \(encodedImage!.count.formatted()) bytes")
                }
            }
        }
    }
}

# Kriptex 

Forked from the project Coatex. This is TOR based peer to peer, encrypted chatting application. The aim of this project is to bring chatting application on TOR, enable users to chat with each other with anonymity in mind. This application is on very initial stages yet it provides text messaging with media sharing capability.

## Screenshots
```To be upload```

## Technical Details

This application creates hidden service using hostname as ID of the application. One has to keep in mind that, it does not include server for routing of messages, all messages are sent directly to other users. There is no online or typing status for the application, but it does indicate that message has been delivered to the user. Moreover, media sharing is slow as compared to other messaging application, because everything runs over Tor. Sharing large files are possible, but you have to rely on the network speed of tor nodes.

## Features

* Encrypted chatting
* HTTPS based, password oriented file sharing
* Dark Mode (can be changed from settings)
* Add requests
* Request notification
* Message notification
* Save media to external storage
* Share media from other applications
* Display picture
* Swipe to tag message
* Message tag
* Media message tag
* Encrypted ID backup and restore

## Getting Started

This application works on Tor binary. If you want to build it yourself, you need ```Android Studio``` for compilation. You need to install one ```BKS``` keystore into ```asset\certificates``` having name ```coatex.bks```. If you want to have ```keystore``` with different name, make sure same name in ```com.ivor.coatex.tor.FileServer``` file. This keystore is necessary for running https server on Android.

### Prerequisites

This source code is ready to build and use but if you want to add some more features you can clone and add your features too. This is very initial code base, it might contain bugs. It includes pre compiled ```latest version``` binary. You can compile your own version of ```Tor``` and include in this project yourself.

## Built With

I have used many open-source libraries. These libraries can be found in app.gradle file.

## License

This project is licensed under the GPL v3 License - see the [LICENSE.md](https://raw.githubusercontent.com/moonchitta/Coatex/master/LICENSE) file for details

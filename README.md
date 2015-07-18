WebSMS Connector: TescoIE
=========================

This app sends text messages via www.tescomobile.ie web site.
So you need to have a phone number from Tesco Mobile Ireland
and be registered on www.tescomobile.ie web site.

This app is a connector for the WebSMS android app.
So you need to install WebSMS before you can use this app.


Build
=====

Gradle handles the remote maven dependency for the WebSMS API automatically.

Simply build the connector by running `./gradlew clean assemble` on Linux or `gradlew.bat clean assemble` on Windows.

You can also import this project into Android Studio.

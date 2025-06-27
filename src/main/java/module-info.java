module com.example.ftpservers {
    requires javafx.controls;
    requires javafx.fxml;

    exports com.example.ftpservers.Client;  // 导出控制器包
    opens com.example.ftpservers.Client to javafx.fxml;
}

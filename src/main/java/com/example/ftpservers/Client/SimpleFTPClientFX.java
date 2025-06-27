package com.example.ftpservers.Client;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

public class SimpleFTPClientFX extends Application {

    @Override
    public void start(Stage primaryStage) throws Exception {
        openNewWindow();
    }

    public void openNewWindow() throws Exception {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/example/ftpservers/SimpleFTPClientFX.fxml"));
        Parent root = loader.load();
        Stage stage = new Stage();
        stage.setTitle("Simple FTP Client");
        stage.setScene(new Scene(root, 800, 500));
        stage.show();

    }

    public static void main(String[] args) {
        launch(args);
    }
}


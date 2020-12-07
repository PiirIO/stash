public class Test {

    public static void main(String[] args){

        int limit = 10;

        for(int i = 0; i <= limit; i++){
            if(i<=5) {
                System.out.println(i);
                continue;
            }
            break;
        }
    }
}
